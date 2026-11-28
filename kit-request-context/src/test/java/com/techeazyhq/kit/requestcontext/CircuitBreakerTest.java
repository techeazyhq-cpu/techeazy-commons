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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private final CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(30), nanos::get);

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    void itStaysClosedBelowTheThresholdAndASuccessResetsTheCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(Duration.ZERO, breaker.permit());
    }

    @Test
    void consecutiveFailuresOpenItAndAnOpenCircuitSaysHowLongItStaysOpen() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertEquals(Duration.ofSeconds(30), breaker.permit());
        advance(Duration.ofSeconds(10));
        assertEquals(Duration.ofSeconds(20), breaker.permit());
    }

    @Test
    void afterTheOpenTimeExactlyOneTrialIsLetThroughAndTheOthersWait() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        advance(Duration.ofSeconds(31));

        assertEquals(Duration.ZERO, breaker.permit(), "the trial");
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.permit().compareTo(Duration.ZERO) > 0, "a second caller waits for the trial's verdict");
    }

    @Test
    void aSuccessfulTrialClosesItAndAFailedOneOpensItAgainForAnotherFullPeriod() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        advance(Duration.ofSeconds(31));
        breaker.permit();
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());

        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        advance(Duration.ofSeconds(31));
        breaker.permit();
        breaker.recordFailure();

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertEquals(Duration.ofSeconds(30), breaker.permit());
    }
}
