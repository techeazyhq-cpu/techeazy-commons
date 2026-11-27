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

package com.techeazyhq.kit.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionTest {

    @Test
    void anAllowWithItsObligationsIsRead() {
        Decision d = Decision.parse(Map.of("allow", true, "undetermined", false, "reasons", List.of(), "break_glass", true,
                "obligations", Map.of("audit", true, "review", Map.of("required", true, "due_hours", 24))));

        assertTrue(d.allow());
        assertFalse(d.undetermined());
        assertTrue(d.breakGlass());
        assertEquals(true, d.obligations().get("audit"));
    }

    @Test
    void aDenyKeepsItsReasonsForTheLog() {
        Decision d = Decision.parse(Map.of("allow", false, "reasons", List.of("cross-tenant", "no-care-relationship")));

        assertFalse(d.allow());
        assertEquals(List.of("cross-tenant", "no-care-relationship"), d.reasons());
    }

    @Test
    void aMissingOrNonBooleanAllowIsNotUnderstoodAndNeverDefaulted() {
        assertThrows(IllegalArgumentException.class, () -> Decision.parse(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Decision.parse(Map.of("allow", "true")));
        assertThrows(IllegalArgumentException.class, () -> Decision.parse(Map.of("allow", 1)));
    }

    @Test
    void otherFieldsOfTheWrongTypeAreNotUnderstood() {
        assertThrows(IllegalArgumentException.class, () -> Decision.parse(Map.of("allow", true, "reasons", "x")));
        assertThrows(IllegalArgumentException.class, () -> Decision.parse(Map.of("allow", true, "reasons", List.of(1))));
        assertThrows(IllegalArgumentException.class, () -> Decision.parse(Map.of("allow", true, "obligations", List.of())));
        assertThrows(IllegalArgumentException.class, () -> Decision.parse(Map.of("allow", true, "undetermined", "no")));
    }

    @Test
    void aDecisionIsNeverBothAllowedAndUndetermined() {
        assertThrows(IllegalArgumentException.class, () -> Decision.parse(Map.of("allow", true, "undetermined", true)));
    }
}
