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

package com.techeazyhq.kit.requestcontext;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An internal service a call may be made to, and what a token for it is for: the {@code audience} (the name the service checks its
 * tokens against) and the {@code scopes} the call needs. The token sent to it is exchanged for exactly this and for no other service.
 */
public record OutboundTarget(String audience, Set<String> scopes) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9:._-]{1,128}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");

    public OutboundTarget {
        if (audience == null || !NAME.matcher(audience).matches()) {
            throw new IllegalArgumentException("an audience is a plain name");
        }
        Objects.requireNonNull(scopes, "scopes");
        for (String scope : scopes) {
            if (scope == null || !NAME.matcher(scope).matches()) {
                throw new IllegalArgumentException("a scope is a plain name");
            }
        }
        scopes = Set.copyOf(new LinkedHashSet<>(scopes));
    }

    public static OutboundTarget of(String audience, String... scopes) {
        return new OutboundTarget(audience, Set.of(scopes));
    }

    /**
     * The internal services of a deployment from its setting: {@code host=audience} entries separated by commas, each with optional
     * scopes after a {@code #}, separated by {@code +}: {@code clinical.internal=ogh-clinical-svc#read+write,accounts.internal=ogh-accounts-svc}.
     * An empty setting is no internal service. Anything malformed is an error: a typo must not silently drop a service.
     */
    public static Map<String, OutboundTarget> parse(String setting) {
        Map<String, OutboundTarget> targets = new LinkedHashMap<>();
        if (setting == null || setting.isBlank()) {
            return targets;
        }
        for (String entry : setting.split(",")) {
            String trimmed = entry.strip();
            int eq = trimmed.indexOf('=');
            if (eq < 1 || eq == trimmed.length() - 1) {
                throw new IllegalArgumentException("an internal service is host=audience, not: " + trimmed);
            }
            String host = trimmed.substring(0, eq).strip().toLowerCase(java.util.Locale.ROOT);
            if (!HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("not a host name: " + host);
            }
            String rest = trimmed.substring(eq + 1).strip();
            int hash = rest.indexOf('#');
            String audience = hash < 0 ? rest : rest.substring(0, hash);
            Set<String> scopes = new LinkedHashSet<>();
            if (hash >= 0) {
                for (String scope : rest.substring(hash + 1).split("\\+")) {
                    if (!scope.isBlank()) {
                        scopes.add(scope.strip());
                    }
                }
            }
            if (targets.put(host, new OutboundTarget(audience.strip(), scopes)) != null) {
                throw new IllegalArgumentException("the host is listed twice: " + host);
            }
        }
        return targets;
    }
}
