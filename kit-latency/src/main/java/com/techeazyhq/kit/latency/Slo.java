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

/**
 * The latency targets for a kind of request, as a product's non-functional baseline states them. {@link #READ} and
 * {@link #WRITE} hold the TechEazyHQ defaults (p95 300 and 600 ms, p99 1 and 2 s; OneGreenHealth's baseline,
 * docs/standards/nfr-baseline.md, is the reference); a product with other targets makes its own {@code Slo}. The test gates on
 * {@link #gateP95Ms()}: the p95 target scaled by the 70% rule, so 30% is left for what a test in one process does not see
 * (the gateway, the network, tail and growth). p99 is gated at its target.
 */
public record Slo(String kind, double p95TargetMs, double p99TargetMs) {

    /** The share of the p95 target the components may use (the budget rule of the reference baseline: components add up to at most 70% of the target). */
    public static final double BUDGET_SHARE = 0.70;

    /** Reads: p95 under 300 ms, p99 under 1 s. */
    public static final Slo READ = new Slo("read", 300, 1000);

    /** Writes: p95 under 600 ms, p99 under 2 s. */
    public static final Slo WRITE = new Slo("write", 600, 2000);

    public Slo {
        if (p95TargetMs <= 0 || p99TargetMs < p95TargetMs) {
            throw new IllegalArgumentException("p99 cannot be below p95, and both are positive");
        }
    }

    /** What the p95 of a measured run must stay under. */
    public double gateP95Ms() {
        return p95TargetMs * BUDGET_SHARE;
    }

    public double gateP99Ms() {
        return p99TargetMs;
    }
}
