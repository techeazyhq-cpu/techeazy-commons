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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Judges a measured workload against the product's NFR budget. It fails when:
 * <ul>
 *   <li>there are too few samples for a p95 or a p99 to mean anything ({@link #MIN_SAMPLES});</li>
 *   <li>any request failed: a failed request is not a fast one;</li>
 *   <li>p95 is over the SLO's gate (the p95 target times the 70% rule), or p99 is over its target;</li>
 *   <li>any step is over {@link #STEP_FAILURE_FACTOR} times its own budget (a step slightly over budget is reported, not
 *       failed, because one process on a busy machine is noisy; ten times over is a regression).</li>
 * </ul>
 */
public final class LatencyGate {

    /** Below this a percentile is an anecdote. */
    public static final int MIN_SAMPLES = 100;

    /** A step this many times over its budget fails the run; between 1 and this it is reported as over. */
    public static final double STEP_FAILURE_FACTOR = 3.0;

    private LatencyGate() {
    }

    public record StepResult(String name, double p95Ms, double budgetP95Ms, boolean overBudget, boolean failed) {
    }

    public record Verdict(String route, String kind, int count, int failures, double p50Ms, double p95Ms, double p99Ms,
                          double maxMs, double gateP95Ms, double gateP99Ms, List<StepResult> steps, List<String> reasons) {

        public boolean passed() {
            return reasons.isEmpty();
        }
    }

    public static Verdict evaluate(Measurement m) {
        Samples s = m.samples();
        List<String> reasons = new ArrayList<>();
        if (s.count() < MIN_SAMPLES) {
            reasons.add("only " + s.count() + " samples, at least " + MIN_SAMPLES + " are needed to judge p95 and p99");
        }
        if (s.failures() > 0) {
            reasons.add(s.failures() + " request(s) failed: a failed request is not a fast one");
        }
        if (s.count() == 0) {
            return new Verdict(m.route(), m.slo().kind(), 0, s.failures(), 0, 0, 0, 0, m.slo().gateP95Ms(), m.slo().gateP99Ms(),
                    List.of(), List.copyOf(reasons));
        }
        double p50 = s.percentileMs(50);
        double p95 = s.percentileMs(95);
        double p99 = s.percentileMs(99);
        if (p95 > m.slo().gateP95Ms()) {
            reasons.add(format("p95 %.1f ms is over the gate of %.1f ms (%.0f ms target, 70%% rule)", p95, m.slo().gateP95Ms(),
                    m.slo().p95TargetMs()));
        }
        if (p99 > m.slo().gateP99Ms()) {
            reasons.add(format("p99 %.1f ms is over the target of %.1f ms", p99, m.slo().gateP99Ms()));
        }
        List<StepResult> steps = new ArrayList<>();
        for (Measurement.Step step : m.orderedSteps()) {
            double stepP95 = step.samples().count() == 0 ? 0 : step.samples().percentileMs(95);
            boolean over = stepP95 > step.budgetP95Ms();
            boolean failed = stepP95 > step.budgetP95Ms() * STEP_FAILURE_FACTOR;
            steps.add(new StepResult(step.name(), stepP95, step.budgetP95Ms(), over, failed));
            if (failed) {
                reasons.add(format("step '%s' p95 %.1f ms is more than %.0f times its budget of %.1f ms", step.name(), stepP95,
                        STEP_FAILURE_FACTOR, step.budgetP95Ms()));
            }
        }
        return new Verdict(m.route(), m.slo().kind(), s.count(), s.failures(), p50, p95, p99, s.maxMs(), m.slo().gateP95Ms(),
                m.slo().gateP99Ms(), List.copyOf(steps), List.copyOf(reasons));
    }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
