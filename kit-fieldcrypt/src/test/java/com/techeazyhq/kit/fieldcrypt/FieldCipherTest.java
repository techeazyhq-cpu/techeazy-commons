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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** A database dump holds sealed strings and nothing else; a sealed value opens only where it was sealed. */
class FieldCipherTest {

    private static final String TENANT_A = "tenant:" + UUID.randomUUID();
    private static final String TENANT_B = "tenant:" + UUID.randomUUID();
    private static final String SECRET = "Type 2 diabetes with early nephropathy";

    private final InMemoryKeyEncryptionKeys keys = new InMemoryKeyEncryptionKeys();
    private final FieldCipher cipher = new FieldCipher(keys);

    private static FieldContext ctx(String key, String field, String row) {
        return new FieldContext(key, field, row);
    }

    private String seal(String value, FieldContext context) {
        return cipher.encrypt(value, context).block();
    }

    @Test
    void aValueOpensToWhatWasSealed() {
        FieldContext c = ctx(TENANT_A, "patient.diagnosis", "row-1");

        StepVerifier.create(cipher.decrypt(seal(SECRET, c), c)).expectNext(SECRET).verifyComplete();
    }

    @Test
    void whatIsStoredHoldsNoPlaintextAndNoKey() {
        FieldContext c = ctx(TENANT_A, "patient.diagnosis", "row-1");
        String sealed = seal(SECRET, c);

        assertTrue(sealed.startsWith("fe1." + TENANT_A + "."));
        assertFalse(sealed.contains("diabetes") || sealed.contains("nephropathy"));
        for (String word : SECRET.split(" ")) {
            if (word.length() < 4) {
                continue; // a short word such as "2" or "with" can appear in base64 by chance
            }
            assertFalse(sealed.toLowerCase().contains(word.toLowerCase()), word);
        }
        for (String part : sealed.split("\\.")) {
            try {
                assertFalse(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8).contains("diabetes"));
            } catch (IllegalArgumentException notBase64) {
                // the prefix and the key id are not base64
            }
        }
    }

    @Test
    void twoSealsOfTheSameValueDiffer() {
        FieldContext c = ctx(TENANT_A, "patient.diagnosis", "row-1");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(seal(SECRET, c));
        }

        assertEquals(200, seen.size(), "equal values must not be visible as equal");
    }

    @Test
    void aValueMovedToAnotherRowColumnOrTenantDoesNotOpen() {
        FieldContext original = ctx(TENANT_A, "patient.diagnosis", "row-1");
        String sealed = seal(SECRET, original);

        for (FieldContext other : new FieldContext[] {ctx(TENANT_A, "patient.diagnosis", "row-2"),
                ctx(TENANT_A, "patient.allergy", "row-1")}) {
            StepVerifier.create(cipher.decrypt(sealed, other)).expectError(FieldDecryptionException.class).verify();
        }
        StepVerifier.create(cipher.decrypt(sealed, ctx(TENANT_B, "patient.diagnosis", "row-1")))
                .expectError(FieldDecryptionException.class).verify();
    }

    @Test
    void aValueSealedForTenantAWithItsKeyIdChangedToTenantBStillDoesNotOpen() {
        FieldContext a = ctx(TENANT_A, "patient.diagnosis", "row-1");
        seal("tenant B exists", ctx(TENANT_B, "patient.diagnosis", "row-0"));
        String forged = seal(SECRET, a).replace(TENANT_A, TENANT_B);

        StepVerifier.create(cipher.decrypt(forged, ctx(TENANT_B, "patient.diagnosis", "row-1")))
                .expectError(FieldDecryptionException.class).verify();
    }

    @Test
    void changingAnyPartOfASealedValueIsRefusedWithTheSameErrorWhateverTheCause() {
        FieldContext c = ctx(TENANT_A, "patient.diagnosis", "row-1");
        String[] parts = seal(SECRET, c).split("\\.");
        String message = null;
        for (int part = 2; part < 5; part++) {
            String[] copy = parts.clone();
            byte[] bytes = Base64.getUrlDecoder().decode(copy[part]);
            bytes[bytes.length / 2] ^= 1;
            copy[part] = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

            FieldDecryptionException failure = assertThrows(FieldDecryptionException.class,
                    () -> cipher.decrypt(String.join(".", copy), c).block());
            message = message == null ? failure.getMessage() : message;
            assertEquals(message, failure.getMessage(), "the same message for every cause");
        }
        for (String bad : new String[] {"", "fe1", "fe1.a.b.c", "fe2." + TENANT_A + ".a.b.c", "plain text", "fe1." + TENANT_A + ".!!.!!.!!",
                "fe1." + TENANT_A + "..."}) {
            assertThrows(FieldDecryptionException.class, () -> cipher.decrypt(bad, c).block(), bad);
        }
    }

    @Test
    void destroyingTheTenantKeyErasesEveryValueOfThatTenantAndOnlyThat() {
        FieldContext a = ctx(TENANT_A, "patient.diagnosis", "row-1");
        FieldContext b = ctx(TENANT_B, "patient.diagnosis", "row-1");
        String sealedA = seal(SECRET, a);
        String sealedB = seal(SECRET, b);

        keys.destroy(TENANT_A);

        StepVerifier.create(cipher.decrypt(sealedA, a)).expectError(KeyDestroyedException.class).verify();
        StepVerifier.create(cipher.decrypt(sealedB, b)).expectNext(SECRET).verifyComplete();
        StepVerifier.create(cipher.encrypt("new", a)).expectError(KeyDestroyedException.class).verify();
    }

    @Test
    void nullStaysNullAndEmptyAndNonAsciiRoundTrip() {
        FieldContext c = ctx(TENANT_A, "patient.note", "row-1");

        assertEquals(null, cipher.encrypt((String) null, c).block());
        assertEquals(null, cipher.decrypt((String) null, c).block());
        for (String text : new String[] {"", "रोगी को मधुमेह है", "a".repeat(10_000), "line\nbreak and \u0000 nul"}) {
            assertEquals(text, cipher.decrypt(seal(text, c), c).block());
        }
        assertEquals(3, cipher.decryptBytes(cipher.encrypt(new byte[] {1, 2, 3}, c).block(), c).block().length);
    }

    @Test
    void aFieldIsBoundedInSize() {
        FieldContext c = ctx(TENANT_A, "patient.note", "row-1");

        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(new byte[FieldCipher.MAX_PLAINTEXT_BYTES + 1], c).block());
        assertEquals(FieldCipher.MAX_PLAINTEXT_BYTES, cipher.decryptBytes(
                cipher.encrypt(new byte[FieldCipher.MAX_PLAINTEXT_BYTES], c).block(), c).block().length);
    }

    @Test
    void aSealedValueIsRecognisedForMigrations() {
        assertTrue(FieldCipher.isSealed(seal(SECRET, ctx(TENANT_A, "f", "r"))));
        assertFalse(FieldCipher.isSealed("Type 2 diabetes"));
        assertFalse(FieldCipher.isSealed(null));
    }

    @Test
    void theContextIsValidatedAndTwoContextsNeverShareBytes() {
        assertThrows(IllegalArgumentException.class, () -> ctx("has space", "f", "r"));
        assertThrows(IllegalArgumentException.class, () -> ctx(TENANT_A, "a b", "r"));
        assertThrows(IllegalArgumentException.class, () -> ctx(TENANT_A, "f", ""));
        assertThrows(NullPointerException.class, () -> ctx(TENANT_A, "f", null));
        assertNotEquals(java.util.Arrays.toString(ctx("a", "bc", "d").aad()), java.util.Arrays.toString(ctx("a", "b", "cd").aad()),
                "the boundary between key, field and row is part of what is authenticated");
    }

    @Test
    void manyThreadsSealAndOpenWithoutMixingValues() {
        java.util.List<String> wrong = reactor.core.publisher.Flux.range(0, 500).flatMap(i -> {
            FieldContext c = ctx(i % 2 == 0 ? TENANT_A : TENANT_B, "patient.diagnosis", "row-" + i);
            return cipher.encrypt("value-" + i, c).flatMap(s -> cipher.decrypt(s, c)).filter(v -> !v.equals("value-" + i));
        }).subscribeOn(reactor.core.scheduler.Schedulers.parallel()).collectList().block();

        assertEquals(java.util.List.of(), wrong);
    }
}
