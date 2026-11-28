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

import java.util.Map;
import java.util.Objects;

/**
 * What a step's action or compensation came back with.
 * <ul>
 *   <li>{@link Done}: it is finished; its {@code data} (ids only) is added to the saga's data for the next steps.</li>
 *   <li>{@link Waiting}: the command was sent (through the outbox) and the answer will arrive as an event, passed to
 *       {@link SagaEngine#signal}; until then the saga waits, up to the step's timeout.</li>
 *   <li>{@link Failed}: the step cannot succeed, however often it is tried (a business refusal). It is not retried.</li>
 * </ul>
 * An exception or a timeout is none of these: it is a failed attempt, retried up to the step's limit.
 */
public sealed interface StepOutcome {

    record Done(Map<String, String> data) implements StepOutcome {
        public Done {
            data = Map.copyOf(Objects.requireNonNull(data, "data"));
        }
    }

    record Waiting() implements StepOutcome {
    }

    /** {@code code} is a short code for logs and the manual queue, never text about a person. */
    record Failed(String code) implements StepOutcome {
        public Failed {
            if (code == null || !code.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("a failure code is lower-case words with dashes");
            }
        }
    }

    static StepOutcome done() {
        return new Done(Map.of());
    }

    static StepOutcome done(Map<String, String> data) {
        return new Done(data);
    }

    static StepOutcome waiting() {
        return new Waiting();
    }

    static StepOutcome failed(String code) {
        return new Failed(code);
    }
}
