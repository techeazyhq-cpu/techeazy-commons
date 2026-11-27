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

/**
 * What a caller does with a consent check, after the fail-closed rules ({@link ConsentEnforcer}). A caller switches on
 * this, not on {@link ConsentDecision}: it has no case for "the adapter failed", because that has been turned into
 * {@link TryLater} already.
 * <ul>
 *   <li>{@link Proceed}: process the data, and audit the basis.</li>
 *   <li>{@link Refused}: consent says no. Do not process. The reason is a code.</li>
 *   <li>{@link TryLater}: nobody could say. Do not process, do <b>not</b> ask the person for consent again (they have not
 *       refused), do not fall back to a cached or assumed answer; try again after the interval.</li>
 * </ul>
 */
public sealed interface ConsentOutcome {

    record Proceed(ConsentDecision.Basis basis) implements ConsentOutcome {
        public Proceed {
            Objects.requireNonNull(basis, "basis");
        }
    }

    record Refused(String reason) implements ConsentOutcome {
        public Refused {
            // validated as a deny reason, so the same code rules apply
            new ConsentDecision.Deny(reason);
        }
    }

    record TryLater(Duration retryAfter) implements ConsentOutcome {
        public TryLater {
            new ConsentDecision.Unavailable(retryAfter);
        }
    }

    /** True only for {@link Proceed}. */
    default boolean mayProcess() {
        return this instanceof Proceed;
    }

    /** Whether the person may be sent a consent request on this outcome: never on {@link TryLater}, they have not refused. */
    default boolean mayAskPersonForConsent() {
        return this instanceof Refused;
    }
}
