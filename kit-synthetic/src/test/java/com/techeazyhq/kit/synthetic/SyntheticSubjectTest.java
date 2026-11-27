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

package com.techeazyhq.kit.synthetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SyntheticSubjectTest {

    private final RandomGenerator random = new SplittableRandom(7);

    @Test
    void aSyntheticIdStartsWithTheReservedMarkerAndIsAValidUuid() {
        UUID id = SyntheticSubject.newId(random);

        assertTrue(id.toString().startsWith("53594e00-"), id.toString());
        assertEquals(8, id.version());
        assertEquals(2, id.variant());
        assertTrue(SyntheticSubject.isSyntheticId(id));
    }

    @Test
    void idsFromTheSameSeedAreTheSameAndAreDistinctWithinARun() {
        assertEquals(SyntheticSubject.newId(new SplittableRandom(1)), SyntheticSubject.newId(new SplittableRandom(1)));
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(SyntheticSubject.newId(random)));
        }
    }

    @Test
    void aRandomUuidIsNotSynthetic() {
        for (int i = 0; i < 1_000; i++) {
            assertFalse(SyntheticSubject.isSyntheticId(UUID.randomUUID()));
        }
        assertFalse(SyntheticSubject.isSyntheticId(null));
    }

    @Test
    void theReferenceIsThePrefixAndTheId() {
        UUID id = SyntheticSubject.newId(random);
        String ref = SyntheticSubject.ref(id);

        assertEquals("syn:" + id, ref);
        assertTrue(SyntheticSubject.isSyntheticRef(ref));
        assertEquals(id, SyntheticSubject.requireSyntheticRef(ref));
    }

    @Test
    void aReferenceForARealLookingIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SyntheticSubject.ref(UUID.randomUUID()));
    }

    @Test
    void onlyAWellFormedPrefixedSyntheticReferenceIsAccepted() {
        UUID id = SyntheticSubject.newId(random);
        String[] refused = {
                null, "", "syn:", "syn:not-a-uuid", UUID.randomUUID().toString(), "syn:" + UUID.randomUUID(),
                "SYN:" + id, " syn:" + id, "syn:" + id + " ", "syn:" + id.toString().toUpperCase(), "patient:" + id};

        for (String ref : refused) {
            assertFalse(SyntheticSubject.isSyntheticRef(ref), "accepted: " + ref);
            assertThrows(IllegalArgumentException.class, () -> SyntheticSubject.requireSyntheticRef(ref));
        }
    }
}
