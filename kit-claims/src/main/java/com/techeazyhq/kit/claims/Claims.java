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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who is calling and on what footing, as one validated value: the person, the tenant they belong to, their roles, any
 * further attributes the product's identity provider gives them, and, for a request, the purpose of use.
 *
 * <p>The core is the same for every product: a subject, a tenant (the unit of isolation) and roles. What else a person is,
 * in a product's domain, is an <b>attribute</b>: a named set of plain tokens. A hospital product has {@code facility_ids} and
 * {@code practitioner_id}; a school has {@code campus_ids}; a bank has {@code branch_ids}. The product names the attributes it
 * wants in {@link ClaimNames} and reads them back with {@link #attribute}; nothing in this library knows what they mean.
 *
 * <p>Every part but the purpose comes from the <b>verified token</b> ({@link ClaimsParser}); a service never takes any of it
 * from a header or a body the caller writes. The purpose of use is a statement of the request, validated by shape here and
 * against the product's catalogue by the product. A downstream service gets the same claims because it is sent the same
 * token and reads them from it, not because they are copied into headers that anyone could set.
 *
 * <p>Every identifier is checked to be a short, plain token (letters, digits and {@code : . _ - @}), so a claim cannot carry a
 * line break into a log, a space-separated list into a header or a sentence into a policy input.
 *
 * @param subject    the person (the token's {@code sub})
 * @param tenantId   the tenant they belong to; the unit of isolation
 * @param roles      their roles (may be empty: signed in, allowed nothing)
 * @param attributes the product's own attributes of the person: name to a set of plain tokens (may be empty)
 * @param purpose    the purpose of use of this request, if it has been stated; otherwise null
 */
public record Claims(String subject, UUID tenantId, Set<String> roles, Map<String, Set<String>> attributes, String purpose) {

    /** At most this many roles, attributes and values per attribute: a token with thousands is not a person. */
    public static final int MAX_ROLES = 50;
    public static final int MAX_ATTRIBUTES = 20;
    public static final int MAX_VALUES = 100;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9:._@-]{1,128}");
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern PURPOSE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    public Claims {
        require(subject, "subject");
        Objects.requireNonNull(tenantId, "tenantId");
        roles = copyOf(roles, MAX_ROLES, "roles");
        attributes = copyOf(attributes);
        if (purpose != null && !PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("purpose must be a code: upper-case letters, digits and underscores, 3 to 64");
        }
    }

    /** A person with no attributes and no purpose. */
    public Claims(String subject, UUID tenantId, Set<String> roles) {
        this(subject, tenantId, roles, Map.of(), null);
    }

    /** The same claims with the purpose of use this request stated. */
    public Claims withPurpose(String purposeCode) {
        return new Claims(subject, tenantId, roles, attributes, purposeCode);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** The values of a product attribute; empty when the person has none. */
    public Set<String> attribute(String name) {
        return attributes.getOrDefault(name, Set.of());
    }

    /** The value of an attribute that has exactly one; empty when it has none or several. */
    public Optional<String> attributeValue(String name) {
        Set<String> values = attribute(name);
        return values.size() == 1 ? Optional.of(values.iterator().next()) : Optional.empty();
    }

    public boolean hasAttribute(String name, String value) {
        return attribute(name).contains(value);
    }

    /** What is safe to log: ids, role names, and which attributes are present with how many values, never their values. */
    @Override
    public String toString() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        attributes.forEach((name, values) -> counts.put(name, values.size()));
        return "Claims[subject=" + subject + ", tenantId=" + tenantId + ", roles=" + roles + ", attributes=" + counts
                + ", purpose=" + purpose + "]";
    }

    static boolean isToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    static boolean isAttributeName(String value) {
        return value != null && ATTRIBUTE_NAME.matcher(value).matches();
    }

    private static void require(String value, String what) {
        if (!isToken(value)) {
            throw new IllegalArgumentException(what + " must be a plain token (letters, digits and : . _ - @, at most 128)");
        }
    }

    private static Set<String> copyOf(Set<String> values, int max, String what) {
        if (values == null) {
            throw new NullPointerException(what);
        }
        if (values.size() > max) {
            throw new IllegalArgumentException(what + " has more than " + max + " entries");
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            require(value, what + " entry");
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Map<String, Set<String>> copyOf(Map<String, Set<String>> attributes) {
        if (attributes == null) {
            throw new NullPointerException("attributes");
        }
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("attributes has more than " + MAX_ATTRIBUTES + " entries");
        }
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        attributes.forEach((name, values) -> {
            if (!isAttributeName(name)) {
                throw new IllegalArgumentException("an attribute name is lower case letters, digits and underscores, at most 64");
            }
            copy.put(name, copyOf(values, MAX_VALUES, "attribute " + name));
        });
        return Collections.unmodifiableMap(copy);
    }
}
