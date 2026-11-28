/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

package com.techeazyhq.kit.requestcontext;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * One host's circuit. Closed, it lets calls through and counts consecutive failures; at the threshold it opens and refuses calls for
 * {@code openFor}, answering at once instead of waiting on a service that is down. When that time has passed it lets <b>one</b> trial
 * call through (half open): if it succeeds the circuit closes, if it fails the circuit opens again for another {@code openFor}. A
 * success resets the count. Thread-safe.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final long openForNanos;
    private final LongSupplier nanoClock;
    private State state = State.CLOSED;
    private int failures;
    private long openedAt;

    public CircuitBreaker(int threshold, Duration openFor, LongSupplier nanoClock) {
        this.threshold = threshold;
        this.openForNanos = openFor.toNanos();
        this.nanoClock = nanoClock;
    }

    /** Zero when the call may go; otherwise how long the circuit stays open. A half-open circuit lets one call through at a time. */
    public synchronized Duration permit() {
        long now = nanoClock.getAsLong();
        switch (state) {
            case CLOSED:
                return Duration.ZERO;
            case OPEN:
                long left = openForNanos - (now - openedAt);
                if (left > 0) {
                    return Duration.ofNanos(left);
                }
                state = State.HALF_OPEN; // this caller is the trial
                return Duration.ZERO;
            default:
                return Duration.ofSeconds(1); // a trial is already out: the others wait for its verdict
        }
    }

    public synchronized void recordSuccess() {
        failures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN || ++failures >= threshold) {
            state = State.OPEN;
            openedAt = nanoClock.getAsLong();
            failures = 0;
        }
    }

    public synchronized State state() {
        return state;
    }
}
