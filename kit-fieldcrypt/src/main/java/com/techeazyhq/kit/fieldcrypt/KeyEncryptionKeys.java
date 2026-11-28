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

import reactor.core.publisher.Mono;

/**
 * Where the per-tenant keys live, outside the database: a cloud KMS or HSM in an environment, {@link InMemoryKeyEncryptionKeys}
 * in a test. The library never sees a key encryption key, only asks this port to wrap and unwrap data keys and to derive sub-keys,
 * so the key never has to leave the KMS.
 *
 * <p>A key is named by an id that is not secret (for example {@code tenant:3f2...}). Destroying a key is how data is erased
 * ("crypto-shredding"): afterwards {@link #unwrap} and {@link #derive} fail with {@link KeyDestroyedException}, for the values
 * and for every backup of them alike.
 */
public interface KeyEncryptionKeys {

    /** Protects a data key under the key {@code keyId}. {@code context} is authenticated with it (it must match on unwrap). */
    Mono<byte[]> wrap(String keyId, byte[] dataKey, byte[] context);

    /** The data key, or {@link KeyDestroyedException} when the key is gone, or an error when the wrapped key was altered. */
    Mono<byte[]> unwrap(String keyId, byte[] wrapped, byte[] context);

    /** A 32-byte sub-key of {@code keyId} for {@code label} (for a blind index): the same inputs always give the same key. */
    Mono<byte[]> derive(String keyId, String label);
}
