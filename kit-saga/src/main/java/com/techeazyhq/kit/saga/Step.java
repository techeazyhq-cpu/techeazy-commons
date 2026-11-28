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
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** One step: an action, the compensation that undoes it (if there is anything to undo), and how long and how often to try. */
public record Step(String name, Function<StepContext, Mono<StepOutcome>> action,
                   Function<StepContext, Mono<StepOutcome>> compensation, Duration timeout, int maxAttempts, Duration backoff) {

    public Step {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("a step name is lower-case words with dashes");
        }
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(backoff, "backoff");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a step needs a positive timeout: no step waits forever");
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("maxAttempts is 1 to 20");
        }
        if (backoff.isNegative()) {
            throw new IllegalArgumentException("backoff must not be negative");
        }
    }

    public boolean hasCompensation() {
        return compensation != null;
    }
}
