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
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import reactor.core.publisher.Mono;

/**
 * Equality search over encrypted fields. A sealed value differs every time, so it cannot be searched; instead a second column
 * holds a keyed hash (HMAC-SHA-256 under a sub-key derived from the tenant's key and the field name) of the normalised value.
 * To find a person by phone number, hash the number the same way and compare. The hash reveals nothing without the key, is
 * different for every tenant and every field, and is erased with the tenant key.
 *
 * <p>The caller normalises first (trim, lower-case, digits only for a phone number): equal after normalisation means equal in
 * the index. Only equality is possible; there is no range or prefix search, by design. Low-entropy values (a yes/no, a blood
 * group) can be guessed by someone who can compare index values of two rows, so do not index those.
 */
public final class BlindIndex {

    private final KeyEncryptionKeys keys;

    public BlindIndex(KeyEncryptionKeys keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** The index value for {@code normalisedValue} in a field of this key. Null gives an empty Mono: no index entry. */
    public Mono<String> of(String keyId, String field, String normalisedValue) {
        if (normalisedValue == null) {
            return Mono.empty();
        }
        FieldContext check = new FieldContext(keyId, field, "-"); // validates the ids
        return keys.derive(check.keyId(), "blind-index:" + check.field()).map(subKey -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(subKey, "HmacSHA256"));
                return Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(mac.doFinal(normalisedValue.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("index failed", e);
            }
        });
    }
}
