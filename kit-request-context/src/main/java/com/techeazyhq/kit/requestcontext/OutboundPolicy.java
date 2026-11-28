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
import java.util.Objects;

/**
 * How an outbound call behaves when the other side is slow or failing (TM-15): nothing waits for ever, a failure is retried a bounded
 * number of times and only when repeating is safe, and a service that keeps failing is left alone for a while (a circuit breaker per
 * host) instead of being hammered and holding every caller's request.
 *
 * @param connectTimeout   how long to wait for the connection
 * @param attemptTimeout   how long one attempt may take in all, from sending to the whole answer's headers
 * @param maxRetries       retries after the first attempt (0 for none); only for calls that are safe to repeat
 * @param backoff          the wait before the first retry; it doubles each time, with jitter
 * @param maxBackoff       the longest wait between attempts
 * @param breakerFailures  consecutive failures of a host that open its circuit
 * @param breakerOpenFor   how long an open circuit refuses calls before one trial call is let through
 */
public record OutboundPolicy(Duration connectTimeout, Duration attemptTimeout, int maxRetries, Duration backoff, Duration maxBackoff,
                             int breakerFailures, Duration breakerOpenFor) {

    public OutboundPolicy {
        positive(connectTimeout, "connectTimeout");
        positive(attemptTimeout, "attemptTimeout");
        positive(backoff, "backoff");
        positive(maxBackoff, "maxBackoff");
        positive(breakerOpenFor, "breakerOpenFor");
        if (maxRetries < 0 || maxRetries > 5) {
            throw new IllegalArgumentException("maxRetries is 0 to 5: a call is not repeated without end");
        }
        if (breakerFailures < 1) {
            throw new IllegalArgumentException("breakerFailures is at least 1");
        }
    }

    /** 2 s to connect, 5 s per attempt, two retries (100 ms, then 200 ms), a circuit that opens after 5 failures for 30 s. */
    public static OutboundPolicy defaults() {
        return new OutboundPolicy(Duration.ofSeconds(2), Duration.ofSeconds(5), 2, Duration.ofMillis(100), Duration.ofSeconds(1), 5,
                Duration.ofSeconds(30));
    }

    private static void positive(Duration d, String what) {
        Objects.requireNonNull(d, what);
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(what + " must be positive: nothing waits for ever");
        }
    }
}
