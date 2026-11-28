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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import reactor.core.publisher.Mono;

/**
 * Seals and opens one field. Envelope encryption: every value gets its own random 256-bit data key and 96-bit nonce, is
 * encrypted with AES-256-GCM, and the data key is wrapped by the tenant's key in {@link KeyEncryptionKeys}. The result is one
 * plain string, {@code fe1.<keyId>.<wrapped key>.<nonce>.<ciphertext>}, safe to put in a text column or a document field. A
 * database dump holds only that: no plaintext, no key.
 *
 * <ul>
 *   <li>The {@link FieldContext} (key, column, row) is authenticated: a value moved to another row, column or tenant fails.</li>
 *   <li>Any change to any part of the string fails with {@link FieldDecryptionException}; the cause is never said.</li>
 *   <li>Two seals of the same value differ (random key and nonce), so equal values are not visible. Search by equality uses
 *       a {@link BlindIndex}.</li>
 *   <li>Destroying the tenant key makes every value of that tenant, in the database and in every backup, unreadable:
 *       opening then fails with {@link KeyDestroyedException}.</li>
 * </ul>
 * A {@code null} value is not encrypted: it stays null (the caller stores null).
 */
public final class FieldCipher {

    /** The format version and prefix. A change of algorithm is a new prefix, and old values stay readable. */
    public static final String PREFIX = "fe1";

    /** Larger values (documents, images) belong in object storage with their own envelope, not in a field. */
    public static final int MAX_PLAINTEXT_BYTES = 1 << 20;

    private static final int DATA_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final KeyEncryptionKeys keys;
    private final SecureRandom random;

    public FieldCipher(KeyEncryptionKeys keys) {
        this(keys, new SecureRandom());
    }

    FieldCipher(KeyEncryptionKeys keys, SecureRandom random) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** True when the string looks like a value sealed by this library (for a migration that encrypts existing rows). */
    public static boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX + ".");
    }

    /** Seals a text value. A null value gives an empty Mono: store null. */
    public Mono<String> encrypt(String plaintext, FieldContext context) {
        if (plaintext == null) {
            return Mono.empty();
        }
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8), context);
    }

    public Mono<String> encrypt(byte[] plaintext, FieldContext context) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(context, "context");
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            return Mono.error(new IllegalArgumentException("a field holds at most " + MAX_PLAINTEXT_BYTES + " bytes"));
        }
        byte[] aad = context.aad();
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(dataKey);
        random.nextBytes(nonce);
        return keys.wrap(context.keyId(), dataKey, aad).map(wrapped -> {
            try {
                byte[] sealed = gcm(Cipher.ENCRYPT_MODE, dataKey, nonce, aad, plaintext);
                return PREFIX + "." + context.keyId() + "." + B64.encodeToString(wrapped) + "." + B64.encodeToString(nonce) + "."
                        + B64.encodeToString(sealed);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("encryption failed", e);
            } finally {
                Arrays.fill(dataKey, (byte) 0);
            }
        });
    }

    /** Opens a text value sealed by {@link #encrypt(String, FieldContext)} for the same context. */
    public Mono<String> decrypt(String sealed, FieldContext context) {
        if (sealed == null) {
            return Mono.empty();
        }
        return decryptBytes(sealed, context).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    public Mono<byte[]> decryptBytes(String sealed, FieldContext context) {
        Objects.requireNonNull(context, "context");
        String[] parts = sealed == null ? new String[0] : sealed.split("\\.", -1);
        if (parts.length != 5 || !PREFIX.equals(parts[0]) || !parts[1].equals(context.keyId())) {
            // wrong shape, wrong version, or sealed under another tenant's key: nothing is tried and nothing is said
            return Mono.error(new FieldDecryptionException());
        }
        byte[] wrapped;
        byte[] nonce;
        byte[] ciphertext;
        try {
            wrapped = B64D.decode(parts[2]);
            nonce = B64D.decode(parts[3]);
            ciphertext = B64D.decode(parts[4]);
        } catch (IllegalArgumentException e) {
            return Mono.error(new FieldDecryptionException());
        }
        if (nonce.length != NONCE_BYTES) {
            return Mono.error(new FieldDecryptionException());
        }
        byte[] aad = context.aad();
        return keys.unwrap(context.keyId(), wrapped, aad)
                .onErrorMap(e -> !(e instanceof KeyDestroyedException), e -> new FieldDecryptionException())
                .map(dataKey -> {
                    try {
                        return gcm(Cipher.DECRYPT_MODE, dataKey, nonce, aad, ciphertext);
                    } catch (GeneralSecurityException | RuntimeException e) {
                        throw new FieldDecryptionException();
                    } finally {
                        Arrays.fill(dataKey, (byte) 0);
                    }
                });
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }
}
