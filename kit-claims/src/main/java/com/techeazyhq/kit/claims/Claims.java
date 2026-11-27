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
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who is calling and on what footing, as one validated value: the person, the tenant they belong to, the facilities they work
 * at, their roles, their practitioner id and its registry id, and, for a request, the purpose of use.
 *
 * <p>Every part but the purpose comes from the <b>verified token</b> ({@link ClaimsParser}); a service never takes any of it
 * from a header or a body the caller writes. The purpose of use is a statement of the request, validated by shape here and
 * against the product's catalogue by the product. A downstream service gets the same claims because it is sent the same
 * token and reads them from it, not because they are copied into headers that anyone could set.
 *
 * <p>Every identifier is checked to be a short, plain token (letters, digits and {@code : . _ - @}), so a claim cannot carry a
 * line break into a log, a space-separated list into a header or a sentence into a policy input.
 *
 * @param subject        the person (the token's {@code sub})
 * @param tenantId       the tenant they belong to; the unit of isolation
 * @param facilityIds    the facilities they work at (may be empty)
 * @param roles          their roles (may be empty: signed in, allowed nothing)
 * @param practitionerId the id of the practitioner they are, if they are one; otherwise null
 * @param hprId          the practitioner's id in a professional registry (India's HPR); a placeholder, checked for shape only; null if none
 * @param purpose        the purpose of use of this request, if it has been stated; otherwise null
 */
public record Claims(String subject, UUID tenantId, Set<String> facilityIds, Set<String> roles, String practitionerId,
                     String hprId, String purpose) {

    /** At most this many facilities and roles: a token with thousands is not a person. */
    public static final int MAX_FACILITIES = 100;
    public static final int MAX_ROLES = 50;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9:._@-]{1,128}");
    private static final Pattern PURPOSE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    public Claims {
        require(subject, "subject");
        Objects.requireNonNull(tenantId, "tenantId");
        facilityIds = copyOf(facilityIds, MAX_FACILITIES, "facilityIds");
        roles = copyOf(roles, MAX_ROLES, "roles");
        if (practitionerId != null) {
            require(practitionerId, "practitionerId");
        }
        if (hprId != null) {
            require(hprId, "hprId");
        }
        if (purpose != null && !PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("purpose must be a code: upper-case letters, digits and underscores, 3 to 64");
        }
    }

    /** The same claims with the purpose of use this request stated. */
    public Claims withPurpose(String purposeCode) {
        return new Claims(subject, tenantId, facilityIds, roles, practitionerId, hprId, purposeCode);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean worksAt(String facilityId) {
        return facilityIds.contains(facilityId);
    }

    /** True when the person is a practitioner (has a practitioner id). */
    public boolean isPractitioner() {
        return practitionerId != null;
    }

    /** What is safe to log: ids and counts, never the registry id. */
    @Override
    public String toString() {
        return "Claims[subject=" + subject + ", tenantId=" + tenantId + ", facilities=" + facilityIds.size() + ", roles=" + roles
                + ", practitioner=" + (practitionerId != null) + ", purpose=" + purpose + "]";
    }

    static boolean isToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
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
}
