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

package com.techeazyhq.kit.consent;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The three answers a consent check can give, and nothing else:
 * <ul>
 *   <li>{@link Allow}: process. Carries the {@link Basis} it rests on, which the caller must audit.</li>
 *   <li>{@link Deny}: do not process. Carries a short reason code (never a person's data).</li>
 *   <li>{@link Unavailable}: the answer could not be had. It is <b>not a decision</b>: neither allow nor deny. Do not
 *       process, do not ask the person again, retry after the interval. Sensitive and children's purposes fail closed.</li>
 * </ul>
 */
public sealed interface ConsentDecision {

    /** What an {@code ALLOW} rests on. {@code STUB} is for a stub adapter that exists only for synthetic data. */
    enum Basis { LIVE, TOKEN, STUB }

    record Allow(Basis basis) implements ConsentDecision {
        public Allow {
            Objects.requireNonNull(basis, "basis");
        }
    }

    record Deny(String reason) implements ConsentDecision {
        private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,63}");

        public Deny {
            if (reason == null || !CODE.matcher(reason).matches()) {
                throw new IllegalArgumentException("A deny reason is a kebab-case code, such as non-synthetic-subject");
            }
        }
    }

    record Unavailable(Duration retryAfter) implements ConsentDecision {
        public Unavailable {
            if (retryAfter == null || retryAfter.isZero() || retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must be a positive duration");
            }
        }
    }

    /** True only for {@link Allow}. {@link Unavailable} and {@link Deny} both mean: do not process now. */
    default boolean permitsProcessing() {
        return this instanceof Allow;
    }
}
