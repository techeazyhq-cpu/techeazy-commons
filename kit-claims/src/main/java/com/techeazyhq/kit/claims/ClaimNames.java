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

import java.util.List;
import java.util.Objects;

/**
 * Where each part of {@link Claims} is in a token. The defaults are the plain names ({@code sub}, {@code tenant_id},
 * {@code facility_ids}, {@code roles}, {@code practitioner_id}, {@code hpr_id}); a product whose identity provider uses other
 * names, or Keycloak's {@code realm_access.roles}, says so here and nowhere else.
 *
 * @param rolesPath the keys to walk to the roles, for example {@code ["roles"]} or {@code ["realm_access", "roles"]}
 */
public record ClaimNames(String subject, String tenantId, String facilityIds, List<String> rolesPath, String practitionerId,
                         String hprId) {

    public ClaimNames {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(facilityIds, "facilityIds");
        Objects.requireNonNull(practitionerId, "practitionerId");
        Objects.requireNonNull(hprId, "hprId");
        if (rolesPath == null || rolesPath.isEmpty()) {
            throw new IllegalArgumentException("rolesPath names at least one key");
        }
        rolesPath = List.copyOf(rolesPath);
    }

    public static ClaimNames defaults() {
        return new ClaimNames("sub", "tenant_id", "facility_ids", List.of("roles"), "practitioner_id", "hpr_id");
    }

    /** The same names with the roles where Keycloak puts the realm roles by default. */
    public ClaimNames keycloakRealmRoles() {
        return withRolesPath("realm_access", "roles");
    }

    public ClaimNames withRolesPath(String... path) {
        return new ClaimNames(subject, tenantId, facilityIds, List.of(path), practitionerId, hprId);
    }
}
