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

package com.techeazyhq.kit.cqrs;

import static com.techeazyhq.kit.cqrs.Fixtures.AT;
import static com.techeazyhq.kit.cqrs.Fixtures.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationTest {

    @Test
    void equalMapsHaveNoDifference() {
        assertTrue(Reconciliation.compare(Map.of("a", "1"), Map.of("a", "1")).isEmpty());
    }

    @Test
    void theDifferenceNamesKeysAndNeverValues() {
        Reconciliation.Diff diff = Reconciliation.compare(Map.of("a", "secret-1", "b", "secret-2", "c", "same"),
                Map.of("a", "secret-X", "c", "same", "d", "secret-4"));

        assertEquals(Set.of("b"), diff.missing());
        assertEquals(Set.of("d"), diff.unexpected());
        assertEquals(Set.of("a"), diff.changed());
        assertFalse(diff.toString().contains("secret"));
    }

    @Test
    void anEventIsValidated() {
        UUID id = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new EventRecord(id, TENANT, "item", "a", 0, "ItemNamed", AT, "{}"));
        assertThrows(IllegalArgumentException.class, () -> new EventRecord(id, TENANT, "it em", "a", 1, "ItemNamed", AT, "{}"));
        assertThrows(IllegalArgumentException.class, () -> new EventRecord(id, TENANT, "item", "a b", 1, "ItemNamed", AT, "{}"));
        assertThrows(IllegalArgumentException.class, () -> new EventRecord(id, TENANT, "item", "a", 1, "1Bad", AT, "{}"));
        assertThrows(IllegalArgumentException.class, () -> new EventRecord(id, TENANT, "item", "a", 1, "ItemNamed", AT, null));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRecord(id, TENANT, "item", "a", 1, "ItemNamed", AT, "x".repeat(EventRecord.MAX_PAYLOAD + 1)));
    }
}
