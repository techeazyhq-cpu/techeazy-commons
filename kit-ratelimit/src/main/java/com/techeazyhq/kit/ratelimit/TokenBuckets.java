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

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One token bucket per key, in memory, thread-safe. {@link #tryAcquire} takes a token and says how long to wait when there is none.
 * A key is the whole identity of what is limited ({@code tenant:<id>}, {@code caller:<tenant>:<subject>}), so one key never shares a
 * bucket with another and one tenant at its limit cannot slow another. Buckets that have refilled to full carry no state and are
 * dropped when the table grows, so memory is bounded by the number of keys active at once, not by every key ever seen.
 */
public final class TokenBuckets {

    /** The answer: allowed, or how long until a token is available. */
    public record Result(boolean allowed, Duration retryAfter) {
    }

    private static final class Bucket {
        double tokens;
        long last;

        Bucket(double tokens, long last) {
            this.tokens = tokens;
            this.last = last;
        }
    }

    private static final int SWEEP_EVERY = 4096;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;
    private final int maxKeys;
    private int sinceSweep;

    public TokenBuckets() {
        this(System::nanoTime, 100_000);
    }

    /** {@code nanoClock} is a monotonic clock in nanoseconds (a test moves it). */
    public TokenBuckets(LongSupplier nanoClock, int maxKeys) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys is at least 1");
        }
        this.maxKeys = maxKeys;
    }

    public Result tryAcquire(String key, Limit limit) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(limit, "limit");
        long now = nanoClock.getAsLong();
        sweepSometimes(limit, now);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(limit.capacity(), now));
        synchronized (bucket) {
            double refilled = Math.min(limit.capacity(), bucket.tokens + (now - bucket.last) / 1e9 * limit.refillPerSecond());
            bucket.last = now;
            if (refilled >= 1) {
                bucket.tokens = refilled - 1;
                return new Result(true, Duration.ZERO);
            }
            bucket.tokens = refilled;
            long nanos = (long) Math.ceil((1 - refilled) / limit.refillPerSecond() * 1e9);
            return new Result(false, Duration.ofNanos(Math.max(1, nanos)));
        }
    }

    /** How many keys hold a bucket right now. */
    public int size() {
        return buckets.size();
    }

    private void sweepSometimes(Limit limit, long now) {
        if (buckets.size() < maxKeys) {
            return;
        }
        synchronized (this) {
            if (++sinceSweep < SWEEP_EVERY && buckets.size() < maxKeys * 2) {
                return;
            }
            sinceSweep = 0;
            Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
            while (it.hasNext()) {
                Bucket b = it.next().getValue();
                synchronized (b) {
                    // full again: it holds nothing a new bucket would not
                    if (b.tokens + (now - b.last) / 1e9 * limit.refillPerSecond() >= limit.capacity()) {
                        it.remove();
                    }
                }
            }
        }
    }
}
