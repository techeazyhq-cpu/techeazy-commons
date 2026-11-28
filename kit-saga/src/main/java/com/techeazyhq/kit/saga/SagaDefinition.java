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

package com.techeazyhq.kit.saga;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * A saga type: its steps in order and the team that owns what goes to the manual queue. A saga has at least two steps: an
 * operation inside one service is a database transaction, not a saga (STD-SAGA-05).
 */
public final class SagaDefinition {

    private final String type;
    private final String owner;
    private final List<Step> steps;

    private SagaDefinition(String type, String owner, List<Step> steps) {
        this.type = type;
        this.owner = owner;
        this.steps = List.copyOf(steps);
    }

    public String type() {
        return type;
    }

    /** The team (a role) that works the manual-intervention queue for this saga type. */
    public String owner() {
        return owner;
    }

    public List<Step> steps() {
        return steps;
    }

    public static Builder builder(String type, String owner) {
        return new Builder(type, owner);
    }

    public static final class Builder {

        private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

        private final String type;
        private final String owner;
        private final List<Step> steps = new ArrayList<>();

        private Builder(String type, String owner) {
            if (type == null || !type.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("a saga type is lower-case words with dashes");
            }
            if (owner == null || !owner.matches("[a-z][a-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("a saga needs the owning team or role for its manual queue");
            }
            this.type = type;
            this.owner = owner;
        }

        /** A step with no compensation (nothing to undo), 30 s timeout, 3 attempts, 1 s backoff. */
        public Builder step(String name, Function<StepContext, Mono<StepOutcome>> action) {
            steps.add(new Step(name, action, null, DEFAULT_TIMEOUT, 3, Duration.ofSeconds(1)));
            return this;
        }

        /** A step with the compensation that undoes it. */
        public Builder step(String name, Function<StepContext, Mono<StepOutcome>> action,
                            Function<StepContext, Mono<StepOutcome>> compensation) {
            steps.add(new Step(name, action, Objects.requireNonNull(compensation, "compensation"), DEFAULT_TIMEOUT, 3,
                    Duration.ofSeconds(1)));
            return this;
        }

        /** A fully specified step. */
        public Builder step(Step step) {
            steps.add(step);
            return this;
        }

        public SagaDefinition build() {
            if (steps.size() < 2) {
                throw new IllegalArgumentException("a saga spans at least two steps; one service's operation is a transaction");
            }
            Set<String> names = new HashSet<>();
            for (Step step : steps) {
                if (!names.add(step.name())) {
                    throw new IllegalArgumentException("two steps are called " + step.name());
                }
            }
            return new SagaDefinition(type, owner, steps);
        }
    }
}
