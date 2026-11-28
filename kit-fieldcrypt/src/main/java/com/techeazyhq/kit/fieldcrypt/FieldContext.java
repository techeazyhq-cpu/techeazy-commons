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
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a value belongs to: the key that protects it (usually the tenant's), the column or field ({@code patient.diagnosis}),
 * and the row or document it sits in. All three are authenticated with the value, so a sealed value copied to another row,
 * another column or another tenant does not open: an attacker with write access to the database cannot swap one person's
 * record for another's. Pass the same context to {@link FieldCipher#encrypt} and {@link FieldCipher#decrypt}.
 */
public record FieldContext(String keyId, String field, String rowId) {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9:._-]{1,128}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public FieldContext {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("keyId must be a plain id (letters, digits and : . _ -, at most 128)");
        }
        if (field == null || !FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("field must be a plain name such as patient.diagnosis");
        }
        Objects.requireNonNull(rowId, "rowId");
        if (rowId.isEmpty() || rowId.length() > 128) {
            throw new IllegalArgumentException("rowId must be the id of the row or document, 1 to 128 characters");
        }
    }

    /** Length-prefixed so that no two different contexts have the same bytes. */
    byte[] aad() {
        byte[] k = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] f = field.getBytes(StandardCharsets.UTF_8);
        byte[] r = rowId.getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(12 + k.length + f.length + r.length);
        buffer.putInt(k.length).put(k).putInt(f.length).put(f).putInt(r.length).put(r);
        return buffer.array();
    }
}
