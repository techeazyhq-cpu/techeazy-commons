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

package com.techeazyhq.kit.breakglass;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One use of break-glass: who, to whose record, what they did, why (a reason code from the product's closed list, whose
 * values the product's access policy owns), and the justification they gave. It is the record the
 * reviewer reads, and it is health data (data class D4): the justification is free text and may say what was wrong with the
 * patient, so it is kept here and in the review store (encrypted at rest, EP-08-02), never in an event, a log line or
 * {@link #toString()}. What leaves this record is {@link #toEvent()}, which holds ids and codes only.
 */
public record BreakGlassRecord(UUID id, UUID tenantId, UUID facilityId, UUID accessorUserId, String patientRef,
                               String resourceType, String action, String reason, String justification,
                               Instant occurredAt, String correlationId) {

    /** The review must happen within this long of the access (AD-05); a product's access policy should state the same 24 hours. */
    public static final Duration REVIEW_SLA = Duration.ofHours(24);

    static final int MIN_JUSTIFICATION = 10;
    static final int MAX_JUSTIFICATION = 500;

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9:._-]{1,128}");

    public BreakGlassRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(facilityId, "facilityId");
        Objects.requireNonNull(accessorUserId, "accessorUserId");
        opaque(patientRef, "patientRef");
        opaque(resourceType, "resourceType");
        opaque(action, "action");
        if (reason == null || !CODE.matcher(reason).matches()) {
            throw new IllegalArgumentException("reason must be a code from the product's closed list (lower snake case), never text");
        }
        if (justification == null || justification.strip().length() < MIN_JUSTIFICATION
                || justification.length() > MAX_JUSTIFICATION) {
            throw new IllegalArgumentException("A justification of " + MIN_JUSTIFICATION + " to " + MAX_JUSTIFICATION
                    + " characters is mandatory");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        opaque(correlationId, "correlationId");
    }

    /** When the review is due. */
    public Instant reviewDueAt() {
        return occurredAt.plus(REVIEW_SLA);
    }

    /** What may be published: ids and codes, no justification, so no health data. */
    public Event toEvent() {
        return new Event(id, tenantId, facilityId, accessorUserId, patientRef, resourceType, action, reason, occurredAt,
                reviewDueAt(), correlationId);
    }

    /** The thin event (standard: events carry references, not PHI). */
    public record Event(UUID id, UUID tenantId, UUID facilityId, UUID accessorUserId, String patientRef,
                        String resourceType, String action, String reason, Instant occurredAt, Instant reviewDueAt,
                        String correlationId) {
    }

    /** Never prints the justification. */
    @Override
    public String toString() {
        return "BreakGlassRecord[id=" + id + ", tenantId=" + tenantId + ", accessor=" + accessorUserId + ", reason=" + reason
                + ", resource=" + resourceType + ", action=" + action + ", occurredAt=" + occurredAt + "]";
    }

    private static void opaque(String value, String what) {
        if (value == null || !OPAQUE.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must be an opaque reference (letters, digits and : . _ -, at most 128)");
        }
    }
}
