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

package com.techeazyhq.kit.fieldcrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class BlindIndexTest {

    private final InMemoryKeyEncryptionKeys keys = new InMemoryKeyEncryptionKeys();
    private final BlindIndex index = new BlindIndex(keys);

    @Test
    void theSameValueInTheSameFieldOfTheSameTenantAlwaysGivesTheSameIndex() {
        assertEquals(index.of("tenant:a", "patient.phone", "9876543210").block(), index.of("tenant:a", "patient.phone", "9876543210").block());
    }

    @Test
    void anotherTenantAnotherFieldOrAnotherValueGivesAnotherIndex() {
        String base = index.of("tenant:a", "patient.phone", "9876543210").block();

        assertNotEquals(base, index.of("tenant:b", "patient.phone", "9876543210").block());
        assertNotEquals(base, index.of("tenant:a", "patient.alt_phone", "9876543210").block());
        assertNotEquals(base, index.of("tenant:a", "patient.phone", "9876543211").block());
    }

    @Test
    void theIndexRevealsNothingOfTheValue() {
        String value = index.of("tenant:a", "patient.phone", "9876543210").block();

        assertFalse(value.contains("9876"));
        assertEquals(43, value.length(), "a 256-bit hash, base64 without padding");
    }

    @Test
    void nullHasNoIndexEntryAndAShreddedTenantHasNoIndexAtAll() {
        assertEquals(null, index.of("tenant:a", "patient.phone", null).block());

        keys.destroy("tenant:a");

        StepVerifier.create(index.of("tenant:a", "patient.phone", "9876543210")).expectError(KeyDestroyedException.class).verify();
    }

    @Test
    void theIdsAreValidated() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> index.of("bad id", "f", "v"));
    }
}
