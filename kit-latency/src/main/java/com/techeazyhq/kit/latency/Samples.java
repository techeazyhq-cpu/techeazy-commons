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

package com.techeazyhq.kit.latency;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Durations recorded from many threads, and the percentiles of them. */
public final class Samples {

    private final ConcurrentLinkedQueue<Long> nanos = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failures = new AtomicInteger();

    /** A request that completed, and how long it took. */
    public void record(long durationNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("A duration cannot be negative");
        }
        nanos.add(durationNanos);
    }

    /** A request that failed: it is not a fast request, and the gate counts it against the run. */
    public void recordFailure() {
        failures.incrementAndGet();
    }

    public int count() {
        return nanos.size();
    }

    public int failures() {
        return failures.get();
    }

    /**
     * The p-th percentile in milliseconds by the nearest-rank method: the smallest value with at least p% of the samples at
     * or below it. Nearest rank never invents a value that was not observed, and never under-reports the tail.
     */
    public double percentileMs(double p) {
        if (p <= 0 || p > 100) {
            throw new IllegalArgumentException("A percentile is above 0 and at most 100");
        }
        long[] sorted = sorted();
        if (sorted.length == 0) {
            throw new IllegalStateException("No samples");
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1] / 1_000_000.0;
    }

    public double maxMs() {
        long[] sorted = sorted();
        return sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1_000_000.0;
    }

    private long[] sorted() {
        long[] values = nanos.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(values);
        return values;
    }
}
