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

package com.techeazyhq.kit.test;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * "No handler reaches guarded data without the guards." A port marked {@code @GuardedData} (techeazy-commons, kit-dom) reaches data
 * that needs the access policy, and, when it is class D3 or D4, the consent check. Any class in the application or interface
 * layer that depends on such a port must also depend on the access guard, and for D3 and D4 on the consent guard. The adapters
 * (infrastructure), the composition root (boot) and the port itself are not checked: they implement the port or wire it.
 *
 * <p>The annotation and the guards are named by class name, so this library depends on neither.
 */
public final class GuardedDataRule {

    static final String ANNOTATION = "com.techeazyhq.kit.dom.GuardedData";
    private static final Set<String> NEEDS_CONSENT = Set.of("D3", "D4");

    private GuardedDataRule() {
    }

    /**
     * The violations, one line each. {@code consentGuard} may be null for a service with no D3 or D4 port; if there is one, it is a
     * violation that none is named.
     */
    public static List<String> violations(JavaClasses classes, String basePackage, String accessGuard, String consentGuard) {
        List<String> found = new ArrayList<>();
        for (JavaClass user : classes) {
            if (!(inLayer(user, basePackage, "app") || inLayer(user, basePackage, "ifc")) || user.isAnnotatedWith(ANNOTATION)) {
                continue;
            }
            Set<String> uses = user.getDirectDependenciesFromSelf().stream().map(Dependency::getTargetClass)
                    .map(JavaClass::getName).collect(Collectors.toSet());
            Set<String> missing = new TreeSet<>();
            for (JavaClass guarded : classes) {
                if (!guarded.isAnnotatedWith(ANNOTATION) || !uses.contains(guarded.getName())) {
                    continue;
                }
                String classification = guarded.getAnnotationOfType(ANNOTATION).get("classification").map(Object::toString).orElse("");
                if (accessGuard == null || !uses.contains(accessGuard)) {
                    missing.add("the access guard " + accessGuard);
                }
                if (NEEDS_CONSENT.contains(classification) && (consentGuard == null || !uses.contains(consentGuard))) {
                    missing.add("the consent guard " + consentGuard + " (the data is " + classification + ")");
                }
            }
            if (!missing.isEmpty()) {
                found.add(user.getName() + " reaches guarded data and does not use " + String.join(" and ", missing));
            }
        }
        return found;
    }

    private static boolean inLayer(JavaClass type, String basePackage, String layer) {
        return type.getPackageName().startsWith(basePackage + "." + layer);
    }
}
