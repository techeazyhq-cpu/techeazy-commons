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

package com.techeazyhq.kit.claims;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads {@link Claims} from the claims of a token that has <b>already been verified</b> (signature, issuer, expiry): this class
 * trusts what it is given and checks only its shape. It is strict and it never throws: whatever it is handed, the answer is
 * {@link Valid} or {@link Invalid} with a code the caller can log (never the offending value, which may be hostile text).
 *
 * <p>A token without a tenant, or with a tenant that is not a UUID, is invalid: a signed-in caller who belongs to no tenant is
 * allowed nothing, and there is never a default tenant. Roles and the product's attributes may be absent (none granted); when
 * present they must be plain tokens, a string or a list of strings, within the limits of {@link Claims}.
 */
public final class ClaimsParser {

    private ClaimsParser() {
    }

    /** The outcome of reading a token's claims. */
    public sealed interface Result {
    }

    public record Valid(Claims claims) implements Result {
    }

    /** {@code code} is one of the {@code CODE_*} constants. */
    public record Invalid(String code) implements Result {
    }

    public static final String CODE_NO_CLAIMS = "no-claims";
    public static final String CODE_NO_SUBJECT = "no-subject";
    public static final String CODE_NO_TENANT = "no-tenant";
    public static final String CODE_BAD_TENANT = "bad-tenant";
    public static final String CODE_BAD_ROLES = "bad-roles";
    /** One of the product's attributes is present and is not plain tokens. The code does not say which or what it held. */
    public static final String CODE_BAD_ATTRIBUTE = "bad-attribute";

    public static Result parse(Map<String, ?> tokenClaims) {
        return parse(tokenClaims, ClaimNames.defaults());
    }

    public static Result parse(Map<String, ?> tokenClaims, ClaimNames names) {
        if (tokenClaims == null || tokenClaims.isEmpty()) {
            return new Invalid(CODE_NO_CLAIMS);
        }
        try {
            if (!(tokenClaims.get(names.subject()) instanceof String subject) || !Claims.isToken(subject)) {
                return new Invalid(CODE_NO_SUBJECT);
            }
            Object tenant = tokenClaims.get(names.tenantId());
            if (tenant == null) {
                return new Invalid(CODE_NO_TENANT);
            }
            UUID tenantId = uuid(tenant);
            if (tenantId == null) {
                return new Invalid(CODE_BAD_TENANT);
            }
            Set<String> roles = tokens(walk(tokenClaims, names.rolesPath()), Claims.MAX_ROLES);
            if (roles == null) {
                return new Invalid(CODE_BAD_ROLES);
            }
            Map<String, Set<String>> attributes = new LinkedHashMap<>();
            for (Map.Entry<String, String> attribute : names.attributes().entrySet()) {
                Set<String> values = tokens(tokenClaims.get(attribute.getValue()), Claims.MAX_VALUES);
                if (values == null) {
                    return new Invalid(CODE_BAD_ATTRIBUTE);
                }
                if (!values.isEmpty()) {
                    attributes.put(attribute.getKey(), values);
                }
            }
            return new Valid(new Claims(subject, tenantId, roles, attributes, null));
        } catch (RuntimeException e) {
            // nothing above should throw; whatever does, the answer is still "not valid", never an exception
            return new Invalid(CODE_NO_CLAIMS);
        }
    }

    private static UUID uuid(Object value) {
        if (!(value instanceof String s) || s.length() != 36) {
            return null; // UUID.fromString also accepts short forms such as "1-1-1-1-1"; only the full form counts
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The value at a path of keys inside nested maps, or null. */
    private static Object walk(Map<String, ?> claims, List<String> path) {
        Object current = claims;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    /**
     * A claim that is absent (none, the empty set), a single plain token, or a collection of plain tokens, as a set. Null when it
     * is anything else, holds a member that is not a plain token, or has more than {@code max} entries.
     */
    private static Set<String> tokens(Object value, int max) {
        if (value == null) {
            return Set.of();
        }
        if (value instanceof String single) {
            return Claims.isToken(single) ? Set.of(single) : null;
        }
        if (!(value instanceof Collection<?> collection) || collection.size() > max) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object member : collection) {
            if (!(member instanceof String s) || !Claims.isToken(s)) {
                return null;
            }
            out.add(s);
        }
        return out;
    }
}
