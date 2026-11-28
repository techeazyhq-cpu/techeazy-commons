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

package com.techeazyhq.kit.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TokenBucketsTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final TokenBuckets buckets = new TokenBuckets(nanos::get, 100);
    private final Limit limit = new Limit(5, 2); // a burst of 5, then 2 a second

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    void aKeyMayBurstUpToItsCapacityAndNoFurther() {
        for (int i = 0; i < 5; i++) {
            assertTrue(buckets.tryAcquire("k", limit).allowed(), "request " + i);
        }
        TokenBuckets.Result refused = buckets.tryAcquire("k", limit);

        assertFalse(refused.allowed());
        assertEquals(Duration.ofMillis(500), refused.retryAfter(), "the next token is half a second away at 2 a second");
    }

    @Test
    void tokensComeBackAtTheRefillRateAndNeverBeyondTheCapacity() {
        for (int i = 0; i < 5; i++) {
            buckets.tryAcquire("k", limit);
        }
        advance(Duration.ofMillis(500));
        assertTrue(buckets.tryAcquire("k", limit).allowed());
        assertFalse(buckets.tryAcquire("k", limit).allowed());

        advance(Duration.ofHours(1));
        for (int i = 0; i < 5; i++) {
            assertTrue(buckets.tryAcquire("k", limit).allowed());
        }
        assertFalse(buckets.tryAcquire("k", limit).allowed(), "an hour of quiet gives the burst back, not more");
    }

    @Test
    void oneKeyAtItsLimitDoesNotAffectAnother() {
        for (int i = 0; i < 20; i++) {
            buckets.tryAcquire("tenant:a", limit);
        }

        assertFalse(buckets.tryAcquire("tenant:a", limit).allowed());
        for (int i = 0; i < 5; i++) {
            assertTrue(buckets.tryAcquire("tenant:b", limit).allowed(), "tenant b is untouched");
        }
    }

    @Test
    void underThreadsExactlyTheCapacityIsGivenOut() {
        AtomicInteger allowed = new AtomicInteger();
        IntStream.range(0, 400).parallel().forEach(i -> {
            if (buckets.tryAcquire("shared", new Limit(50, 0.000001)).allowed()) {
                allowed.incrementAndGet();
            }
        });

        assertEquals(50, allowed.get());
    }

    @Test
    void bucketsThatAreFullAgainAreDroppedSoMemoryFollowsActiveKeysOnly() {
        for (int i = 0; i < 100; i++) {
            buckets.tryAcquire("old-" + i, limit);
        }
        advance(Duration.ofMinutes(10));
        for (int i = 0; i < 5000; i++) {
            buckets.tryAcquire("new-" + (i % 3), limit);
        }

        assertTrue(buckets.size() < 100, "idle keys were dropped: " + buckets.size());
    }

    @Test
    void aLimitMustAllowSomethingAtARealRate() {
        assertThrows(IllegalArgumentException.class, () -> new Limit(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limit(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new TokenBuckets(nanos::get, 0));
    }
}
