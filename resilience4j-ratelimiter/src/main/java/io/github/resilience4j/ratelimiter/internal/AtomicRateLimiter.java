/*
 *
 *  Copyright 2016 Robert Winkler and Bohdan Storozhuk
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.ratelimiter.internal;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnDrainedEvent;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnFailureEvent;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnSuccessEvent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static java.lang.Long.min;
import static java.lang.System.nanoTime;
import static java.lang.Thread.currentThread;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.locks.LockSupport.parkNanos;

/**
 * {@link AtomicRateLimiter} splits all nanoseconds from the start of epoch into cycles.
 * <p>Each cycle has duration of {@link RateLimiterConfig#getLimitRefreshPeriod} in nanoseconds.
 * <p>By contract on start of each cycle {@link AtomicRateLimiter} should
 * set {@link State#activePermissions} to {@link RateLimiterConfig#getLimitForPeriod}. For the {@link
 * AtomicRateLimiter} callers it is really looks so, but under the hood there is some optimisations
 * that will skip this refresh if {@link AtomicRateLimiter} is not used actively.
 * <p>All {@link AtomicRateLimiter} updates are atomic and state is encapsulated in {@link
 * AtomicReference} to {@link AtomicRateLimiter.State}
 */
public class AtomicRateLimiter implements RateLimiter {

    private final long nanoTimeStart;
    private final String name;
    private final AtomicInteger waitingThreads;
    private final AtomicReference<State> state;
    private final Map<String, String> tags;
    private final RateLimiterEventProcessor eventProcessor;

    public AtomicRateLimiter(String name, RateLimiterConfig rateLimiterConfig) {
        this(name, rateLimiterConfig, emptyMap());
    }

    public AtomicRateLimiter(String name, RateLimiterConfig rateLimiterConfig,
                             Map<String, String> tags) {
        this.name = name;
        this.tags = tags;
        this.nanoTimeStart = nanoTime();

        waitingThreads = new AtomicInteger(0);
        state = new AtomicReference<>(new State(
            rateLimiterConfig, 0, rateLimiterConfig.getLimitForPeriod(), 0
        ));
        eventProcessor = new RateLimiterEventProcessor();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void changeTimeoutDuration(final Duration timeoutDuration) {
        RateLimiterConfig newConfig = RateLimiterConfig.from(state.get().config)
            .timeoutDuration(timeoutDuration)
            .build();
        state.updateAndGet(currentState -> new State(
            newConfig, currentState.activeCycle, currentState.activePermissions,
            currentState.nanosToWait
        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void changeLimitForPeriod(final int limitForPeriod) {
        RateLimiterConfig newConfig = RateLimiterConfig.from(state.get().config)
            .limitForPeriod(limitForPeriod)
            .build();
        state.updateAndGet(currentState -> new State(
            newConfig, currentState.activeCycle, currentState.activePermissions,
            currentState.nanosToWait
        ));
    }

    /**
     * Calculates time elapsed from the class loading.
     */
    private long currentNanoTime() {
        return nanoTime() - nanoTimeStart;
    }

    long getNanoTimeStart() {
        return this.nanoTimeStart;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acquirePermission(final int permits) {
        long timeoutInNanos = state.get().config.getTimeoutDuration().toNanos();
        State modifiedState = updateStateWithBackOff(permits, timeoutInNanos);
        boolean result = waitForPermissionIfNecessary(timeoutInNanos, modifiedState.nanosToWait);
        publishRateLimiterAcquisitionEvent(result, permits);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long reservePermission(final int permits) {
        long timeoutInNanos = state.get().config.getTimeoutDuration().toNanos();
        State modifiedState = updateStateWithBackOff(permits, timeoutInNanos);

        boolean canAcquireImmediately = modifiedState.nanosToWait <= 0;
        if (canAcquireImmediately) {
            publishRateLimiterAcquisitionEvent(true, permits);
            return 0;
        }

        boolean canAcquireInTime = timeoutInNanos >= modifiedState.nanosToWait;
        if (canAcquireInTime) {
            publishRateLimiterAcquisitionEvent(true, permits);
            return modifiedState.nanosToWait;
        }

        publishRateLimiterAcquisitionEvent(false, permits);
        return -1;
    }

    @Override
    public void drainPermissions() {
        AtomicRateLimiter.State prev;
        AtomicRateLimiter.State next;
        do {
            prev = state.get();
            next = calculateNextState(prev.activePermissions, 0, prev);
        } while (!compareAndSet(prev, next));
        if (eventProcessor.hasConsumers()) {
            eventProcessor.consumeEvent(new RateLimiterOnDrainedEvent(name, Math.min(prev.activePermissions, 0)));
        }
    }

    /**
     * Atomically updates the current {@link State} with the results of applying the {@link
     * AtomicRateLimiter#calculateNextState}, returning the updated {@link State}. It differs from
     * {@link AtomicReference#updateAndGet(UnaryOperator)} by constant back off. It means that after
     * one try to {@link AtomicReference#compareAndSet(Object, Object)} this method will wait for a
     * while before try one more time. This technique was originally described in this
     * <a href="https://arxiv.org/abs/1305.5800"> paper</a>
     * and showed great results with {@link AtomicRateLimiter} in benchmark tests.
     *
     * @param timeoutInNanos a side-effect-free function
     * @return the updated value
     */
    private State updateStateWithBackOff(final int permits, final long timeoutInNanos) {
        AtomicRateLimiter.State prev;
        AtomicRateLimiter.State next;
        do {
            prev = state.get();
            // 计算下一个 state的状态值
            next = calculateNextState(permits, timeoutInNanos, prev);
            // 原子更新 state的值
        } while (!compareAndSet(prev, next));
        return next;
    }

    /**
     * Atomically sets the value to the given updated value if the current value {@code ==} the
     * expected value. It differs from {@link AtomicReference#updateAndGet(UnaryOperator)} by
     * constant back off. It means that after one try to {@link AtomicReference#compareAndSet(Object,
     * Object)} this method will wait for a while before try one more time. This technique was
     * originally described in this
     * <a href="https://arxiv.org/abs/1305.5800"> paper</a>
     * and showed great results with {@link AtomicRateLimiter} in benchmark tests.
     *
     * @param current the expected value
     * @param next    the new value
     * @return {@code true} if successful. False return indicates that the actual value was not
     * equal to the expected value.
     */
    private boolean compareAndSet(final State current, final State next) {
        if (state.compareAndSet(current, next)) {
            return true;
        }
        parkNanos(1); // back-off
        return false;
    }

    /**
     * A side-effect-free function that can calculate next {@link State} from current. It determines
     * time duration that you should wait for the given number of permits and reserves it for you,
     * if you'll be able to wait long enough.
     *
     * @param permits        number of permits
     * @param timeoutInNanos max time that caller can wait for permission in nanoseconds
     * @param activeState    current state of {@link AtomicRateLimiter}
     * @return next {@link State}
     */
    private State calculateNextState(final int permits,
                                     final long timeoutInNanos,
                                     final State activeState) {
        // 权限数刷新的周期
        long cyclePeriodInNanos = activeState.config.getLimitRefreshPeriod().toNanos();
        // 每个周期最多多少可用许可证
        int permissionsPerCycle = activeState.config.getLimitForPeriod();

        // 从系统启动到现在经历过的纳秒的时间
        long currentNanos = currentNanoTime();

        // 从系统启动到现在经历过的周期数
        long currentCycle = currentNanos / cyclePeriodInNanos;

        // 当前活跃的周期
        long nextCycle = activeState.activeCycle;
        // 当前的许可证数目
        int nextPermissions = activeState.activePermissions;
        // 如果当前的 周期数 不等于 现有的周期数，说明出现了周期的变更。需要计算新的可用的许可证数目
        if (nextCycle != currentCycle) {
            // 当前经历了多少的 周期跨度
            long elapsedCycles = currentCycle - nextCycle;
            // 跨过的周期数积累下来的许可证数目
            long accumulatedPermissions = elapsedCycles * permissionsPerCycle;

            // 修改为当前的周期
            nextCycle = currentCycle;

            // 下一次的许可证数目
            nextPermissions = (int) min(nextPermissions + accumulatedPermissions,
                permissionsPerCycle);
        }
        // 根据申请的许可证的数据去判定需要等待的时长
        long nextNanosToWait = nanosToWaitForPermission(
            permits, cyclePeriodInNanos, permissionsPerCycle, nextPermissions, currentNanos,
            currentCycle
        );
        // 预留许可证信息，并生成新的state信息
        return reservePermissions(activeState.config, permits, timeoutInNanos, nextCycle,
            nextPermissions, nextNanosToWait);
    }

    /**
     * Calculates time to wait for the required permits of permissions to get accumulated
     *
     * @param permits              permits of required permissions
     * @param cyclePeriodInNanos   current configuration values
     * @param permissionsPerCycle  current configuration values
     * @param availablePermissions currently available permissions, can be negative if some
     *                             permissions have been reserved
     * @param currentNanos         current time in nanoseconds
     * @param currentCycle         current {@link AtomicRateLimiter} cycle    @return nanoseconds to
     *                             wait for the next permission
     */
    private long nanosToWaitForPermission(final int permits,
                                          final long cyclePeriodInNanos,
                                          final int permissionsPerCycle,
                                          final int availablePermissions,
                                          final long currentNanos,
                                          final long currentCycle) {
        if (availablePermissions >= permits) {
            return 0L;
        }
        long nextCycleTimeInNanos = (currentCycle + 1) * cyclePeriodInNanos;
        long nanosToNextCycle = nextCycleTimeInNanos - currentNanos;
        int permissionsAtTheStartOfNextCycle = availablePermissions + permissionsPerCycle;
        int fullCyclesToWait = divCeil(-(permissionsAtTheStartOfNextCycle - permits), permissionsPerCycle);
        return (fullCyclesToWait * cyclePeriodInNanos) + nanosToNextCycle;
    }

    /**
     * Divide two integers and round result to the bigger near mathematical integer.
     *
     * @param x - should be > 0
     * @param y - should be > 0
     */
    private static int divCeil(int x, int y) {
        return (x + y - 1) / y;
    }

    /**
     * Determines whether caller can acquire permission before timeout or not and then creates
     * corresponding {@link State}. Reserves permissions only if caller can successfully wait for
     * permission.
     *
     * @param config
     * @param permits        permits of permissions
     * @param timeoutInNanos max time that caller can wait for permission in nanoseconds
     * @param cycle          cycle for new {@link State}
     * @param permissions    permissions for new {@link State}
     * @param nanosToWait    nanoseconds to wait for the next permission
     * @return new {@link State} with possibly reserved permissions and time to wait
     */
    private State reservePermissions(final RateLimiterConfig config,
                                     final int permits,
                                     final long timeoutInNanos,
                                     final long cycle,
                                     final int permissions,
                                     final long nanosToWait) {
        boolean canAcquireInTime = timeoutInNanos >= nanosToWait;
        int permissionsWithReservation = permissions;
        if (canAcquireInTime) {
            permissionsWithReservation -= permits;
        }
        return new State(config, cycle, permissionsWithReservation, nanosToWait);
    }

    /**
     * If nanosToWait is bigger than 0 it tries to park {@link Thread} for nanosToWait but not
     * longer then timeoutInNanos.
     *
     * @param timeoutInNanos max time that caller can wait
     * @param nanosToWait    nanoseconds caller need to wait
     * @return true if caller was able to wait for nanosToWait without {@link Thread#interrupt} and
     * not exceed timeout
     */
    private boolean waitForPermissionIfNecessary(final long timeoutInNanos,
                                                 final long nanosToWait) {
        boolean canAcquireImmediately = nanosToWait <= 0;
        boolean canAcquireInTime = timeoutInNanos >= nanosToWait;

        if (canAcquireImmediately) {
            return true;
        }
        if (canAcquireInTime) {
            return waitForPermission(nanosToWait);
        }
        waitForPermission(timeoutInNanos);
        return false;
    }

    /**
     * Parks {@link Thread} for nanosToWait.
     * <p>If the current thread is {@linkplain Thread#interrupted}
     * while waiting for a permit then it won't throw {@linkplain InterruptedException}, but its
     * interrupt status will be set.
     *
     * @param nanosToWait nanoseconds caller need to wait
     * @return true if caller was not {@link Thread#interrupted} while waiting
     */
    private boolean waitForPermission(final long nanosToWait) {
        waitingThreads.incrementAndGet();
        long deadline = currentNanoTime() + nanosToWait;
        boolean wasInterrupted = false;
        while (currentNanoTime() < deadline && !wasInterrupted) {
            long sleepBlockDuration = deadline - currentNanoTime();
            parkNanos(sleepBlockDuration);
            wasInterrupted = Thread.interrupted();
        }
        waitingThreads.decrementAndGet();
        if (wasInterrupted) {
            currentThread().interrupt();
        }
        return !wasInterrupted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RateLimiterConfig getRateLimiterConfig() {
        return state.get().config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Metrics getMetrics() {
        return new AtomicRateLimiterMetrics();
    }

    @Override
    public EventPublisher getEventPublisher() {
        return eventProcessor;
    }

    @Override
    public String toString() {
        return "AtomicRateLimiter{" +
            "name='" + name + '\'' +
            ", rateLimiterConfig=" + state.get().config +
            '}';
    }

    /**
     * Get the enhanced Metrics with some implementation specific details.
     *
     * @return the detailed metrics
     */
    public AtomicRateLimiterMetrics getDetailedMetrics() {
        return new AtomicRateLimiterMetrics();
    }

    private void publishRateLimiterAcquisitionEvent(boolean permissionAcquired, int permits) {
        if (!eventProcessor.hasConsumers()) {
            return;
        }
        if (permissionAcquired) {
            eventProcessor.consumeEvent(new RateLimiterOnSuccessEvent(name, permits));
            return;
        }
        eventProcessor.consumeEvent(new RateLimiterOnFailureEvent(name, permits));
    }

    /**
     * <p>{@link AtomicRateLimiter.State} represents immutable state of {@link AtomicRateLimiter}
     * where:
     * <ul>
     * <li>activeCycle - {@link AtomicRateLimiter} cycle number that was used
     * by the last {@link AtomicRateLimiter#acquirePermission()} call.</li>
     * <p>
     * <li>activePermissions - count of available permissions after
     * the last {@link AtomicRateLimiter#acquirePermission()} call.
     * Can be negative if some permissions where reserved.</li>
     * <p>
     * <li>nanosToWait - count of nanoseconds to wait for permission for
     * the last {@link AtomicRateLimiter#acquirePermission()} call.</li>
     * </ul>
     */
    private static class State {

        private final RateLimiterConfig config;

        private final long activeCycle;
        private final int activePermissions;
        private final long nanosToWait;

        private State(RateLimiterConfig config,
                      final long activeCycle, final int activePermissions, final long nanosToWait) {
            this.config = config;
            this.activeCycle = activeCycle;
            this.activePermissions = activePermissions;
            this.nanosToWait = nanosToWait;
        }

    }

    /**
     * Enhanced {@link Metrics} with some implementation specific details
     */
    public class AtomicRateLimiterMetrics implements Metrics {

        private AtomicRateLimiterMetrics() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getNumberOfWaitingThreads() {
            return waitingThreads.get();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getAvailablePermissions() {
            State currentState = state.get();
            State estimatedState = calculateNextState(1, -1, currentState);
            return estimatedState.activePermissions;
        }

        /**
         * @return estimated time duration in nanos to wait for the next permission
         */
        public long getNanosToWait() {
            State currentState = state.get();
            State estimatedState = calculateNextState(1, -1, currentState);
            return estimatedState.nanosToWait;
        }

        /**
         * @return estimated current cycle
         */
        public long getCycle() {
            State currentState = state.get();
            State estimatedState = calculateNextState(1, -1, currentState);
            return estimatedState.activeCycle;
        }

    }
}
