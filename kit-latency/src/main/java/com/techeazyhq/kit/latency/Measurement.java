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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One measured workload: a named route of a kind, its end-to-end samples, and optionally the time spent in named steps
 * (for example {@code database}) with the budget each step has in the product's latency budget table.
 */
public final class Measurement {

    /** A step of the request and the budget it has (p95, ms). */
    public record Step(String name, Samples samples, double budgetP95Ms) {
        public Step {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(samples, "samples");
            if (budgetP95Ms <= 0) {
                throw new IllegalArgumentException("A step budget is positive");
            }
        }
    }

    private final String route;
    private final Slo slo;
    private final Samples samples;
    private final Map<String, Step> steps = new LinkedHashMap<>();

    public Measurement(String route, Slo slo, Samples samples) {
        this.route = Objects.requireNonNull(route, "route");
        this.slo = Objects.requireNonNull(slo, "slo");
        this.samples = Objects.requireNonNull(samples, "samples");
    }

    public Measurement step(String name, Samples stepSamples, double budgetP95Ms) {
        steps.put(name, new Step(name, stepSamples, budgetP95Ms));
        return this;
    }

    public String route() {
        return route;
    }

    public Slo slo() {
        return slo;
    }

    public Samples samples() {
        return samples;
    }

    public Map<String, Step> steps() {
        return Map.copyOf(steps);
    }

    java.util.Collection<Step> orderedSteps() {
        return steps.values();
    }
}
