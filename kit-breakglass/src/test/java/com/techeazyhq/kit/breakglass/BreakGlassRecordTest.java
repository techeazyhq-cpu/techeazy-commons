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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BreakGlassRecordTest {

    static final Instant AT = Instant.parse("2026-11-27T10:00:00Z");
    static final String SECRET = "Patient collapsed in the corridor with chest pain and a history of heart failure";

    static BreakGlassRecord record(UUID tenant, UUID accessor) {
        return new BreakGlassRecord(UUID.randomUUID(), tenant, UUID.randomUUID(), accessor, "patient-77", "encounter", "read",
                "life_threatening_emergency", SECRET, AT, "corr-1");
    }

    @Test
    void aReasonIsACodeFromTheProductsListNeverText() {
        for (String bad : new String[] {null, "", "Chest pain", "UNCONSCIOUS_PATIENT", "has space", "1abc", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> new BreakGlassRecord(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), "patient-77", "encounter", "read", bad, SECRET, AT, "corr-1"),
                    "accepted: " + bad);
        }
        assertEquals("other_emergency", new BreakGlassRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "patient-77", "encounter", "read", "other_emergency", SECRET, AT, "corr-1").reason());
    }

    @Test
    void theReviewIsDue24HoursAfterTheAccessAndThePolicyAgrees() {
        BreakGlassRecord r = record(UUID.randomUUID(), UUID.randomUUID());

        assertEquals(Duration.ofHours(24), BreakGlassRecord.REVIEW_SLA);
        assertEquals(Instant.parse("2026-11-28T10:00:00Z"), r.reviewDueAt());
    }

    @Test
    void aJustificationIsMandatoryAndBounded() {
        UUID t = UUID.randomUUID();
        for (String bad : new String[] {null, "", "   ", "short", "x".repeat(501), "         x"}) {
            assertThrows(IllegalArgumentException.class, () -> new BreakGlassRecord(UUID.randomUUID(), t, UUID.randomUUID(),
                    UUID.randomUUID(), "patient-77", "encounter", "read", "other_emergency", bad, AT, "corr-1"),
                    "accepted: " + bad);
        }
        assertEquals(500, new BreakGlassRecord(UUID.randomUUID(), t, UUID.randomUUID(), UUID.randomUUID(), "patient-77",
                "encounter", "read", "other_emergency", "x".repeat(500), AT, "corr-1").justification().length());
    }

    @Test
    void referencesAreOpaqueNeverAValueOrASentence() {
        for (String bad : new String[] {null, "", "Asha Rao", "has space", "a".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> new BreakGlassRecord(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), bad, "encounter", "read", "other_emergency", SECRET,
                    AT, "corr-1"));
        }
    }

    @Test
    void theJustificationNeverLeavesTheRecordByToStringOrByTheEvent() {
        BreakGlassRecord r = record(UUID.randomUUID(), UUID.randomUUID());

        assertFalse(r.toString().contains("collapsed") || r.toString().contains("heart"));
        assertFalse(r.toEvent().toString().contains("collapsed") || r.toEvent().toString().contains("heart"));
        assertTrue(Arrays.stream(BreakGlassRecord.Event.class.getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase().contains("justification")), "the event has no justification field");
    }

    @Test
    void theEventHoldsIdsAndCodesAndTheDeadline() {
        BreakGlassRecord r = record(UUID.randomUUID(), UUID.randomUUID());
        BreakGlassRecord.Event e = r.toEvent();

        assertEquals(r.id(), e.id());
        assertEquals(r.accessorUserId(), e.accessorUserId());
        assertEquals("patient-77", e.patientRef());
        assertEquals("life_threatening_emergency", e.reason());
        assertEquals(r.reviewDueAt(), e.reviewDueAt());
    }
}
