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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where each part of {@link Claims} is in a token. The defaults are the plain names ({@code sub}, {@code tenant_id},
 * {@code roles}) and no attributes; a product says which attributes it reads and where ({@link #withAttribute}), and, if its
 * identity provider uses other names, or Keycloak's {@code realm_access.roles}, says so here and nowhere else.
 *
 * @param rolesPath  the keys to walk to the roles, for example {@code ["roles"]} or {@code ["realm_access", "roles"]}
 * @param attributes the product's attributes: the name it reads them by, to the claim that holds them
 */
public record ClaimNames(String subject, String tenantId, List<String> rolesPath, Map<String, String> attributes) {

    public ClaimNames {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(tenantId, "tenantId");
        if (rolesPath == null || rolesPath.isEmpty()) {
            throw new IllegalArgumentException("rolesPath names at least one key");
        }
        rolesPath = List.copyOf(rolesPath);
        Objects.requireNonNull(attributes, "attributes");
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!Claims.isAttributeName(entry.getKey())) {
                throw new IllegalArgumentException("an attribute name is lower case letters, digits and underscores, at most 64");
            }
            Objects.requireNonNull(entry.getValue(), "the claim of attribute " + entry.getKey());
        }
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static ClaimNames defaults() {
        return new ClaimNames("sub", "tenant_id", List.of("roles"), Map.of());
    }

    /** The same names with the roles where Keycloak puts the realm roles by default. */
    public ClaimNames keycloakRealmRoles() {
        return withRolesPath("realm_access", "roles");
    }

    public ClaimNames withRolesPath(String... path) {
        return new ClaimNames(subject, tenantId, List.of(path), attributes);
    }

    /** The same names, also reading the attribute {@code name} from the claim {@code claim}. */
    public ClaimNames withAttribute(String name, String claim) {
        Map<String, String> more = new LinkedHashMap<>(attributes);
        more.put(name, claim);
        return new ClaimNames(subject, tenantId, rolesPath, more);
    }
}
