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

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import reactor.core.publisher.Mono;

/**
 * The reference {@link KeyEncryptionKeys}, in memory, for tests and local runs. Keys are created on first use and lost when the
 * process ends, so it must never hold data that has to outlive the process: a real environment uses a KMS adapter. Thread-safe.
 */
public final class InMemoryKeyEncryptionKeys implements KeyEncryptionKeys {

    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> keys = new ConcurrentHashMap<>();
    private final Set<String> destroyed = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<byte[]> wrap(String keyId, byte[] dataKey, byte[] context) {
        Objects.requireNonNull(keyId, "keyId");
        return Mono.fromSupplier(() -> {
            if (destroyed.contains(keyId)) {
                throw new KeyDestroyedException(keyId);
            }
            byte[] kek = keys.computeIfAbsent(keyId, id -> {
                byte[] fresh = new byte[32];
                random.nextBytes(fresh);
                return fresh;
            });
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            try {
                byte[] sealed = gcm(Cipher.ENCRYPT_MODE, kek, nonce, context, dataKey);
                byte[] out = new byte[nonce.length + sealed.length];
                System.arraycopy(nonce, 0, out, 0, nonce.length);
                System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
                return out;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public Mono<byte[]> unwrap(String keyId, byte[] wrapped, byte[] context) {
        return Mono.fromSupplier(() -> {
            byte[] kek = keys.get(keyId);
            if (kek == null || destroyed.contains(keyId)) {
                throw new KeyDestroyedException(keyId);
            }
            if (wrapped.length < 12 + 16) {
                throw new IllegalArgumentException("bad wrapped key");
            }
            byte[] nonce = java.util.Arrays.copyOfRange(wrapped, 0, 12);
            byte[] sealed = java.util.Arrays.copyOfRange(wrapped, 12, wrapped.length);
            try {
                return gcm(Cipher.DECRYPT_MODE, kek, nonce, context, sealed);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("bad wrapped key");
            }
        });
    }

    @Override
    public Mono<byte[]> derive(String keyId, String label) {
        return Mono.fromSupplier(() -> {
            if (destroyed.contains(keyId)) {
                throw new KeyDestroyedException(keyId);
            }
            byte[] kek = keys.computeIfAbsent(keyId, id -> {
                byte[] fresh = new byte[32];
                random.nextBytes(fresh);
                return fresh;
            });
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(kek, "HmacSHA256"));
                return mac.doFinal(("derive:" + label).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Crypto-shredding: the key is destroyed, and every value sealed under it is unreadable from now on. */
    public void destroy(String keyId) {
        destroyed.add(keyId);
        byte[] gone = keys.remove(keyId);
        if (gone != null) {
            java.util.Arrays.fill(gone, (byte) 0);
        }
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }
}
