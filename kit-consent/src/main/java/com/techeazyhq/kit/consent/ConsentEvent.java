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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inbound: a person's consent changed. Ids only, so it can travel as a thin internal event ({@code ConsentStateChanged}).
 * {@code WITHDRAWN} and {@code LAPSED} mean processing for the purpose stops at once.
 */
public record ConsentEvent(UUID tenantId, String subjectRef, PurposeCode purpose, Type type, String consentRef,
                           Instant occurredAt) {

    public enum Type { GIVEN, MODIFIED, RENEWED, REFUSED, WITHDRAWN, LAPSED }

    public ConsentEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Reference.opaque(subjectRef, "subjectRef");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(type, "type");
        Reference.opaque(consentRef, "consentRef");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** Whether processing for the purpose must stop now. */
    public boolean stopsProcessing() {
        return type == Type.WITHDRAWN || type == Type.LAPSED || type == Type.REFUSED;
    }
}
