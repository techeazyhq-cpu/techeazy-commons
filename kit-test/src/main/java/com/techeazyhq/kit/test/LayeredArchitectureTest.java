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

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * Enforces the Clean Architecture dependency rule as a normal test, not a manual
 * review step: Domain depends on nothing internal; Application depends on Domain only;
 * Infrastructure/Interfaces depend on Application+Domain but never on each other. Each
 * service extends this, pointing at its own base package (the {@code -boot} module's test
 * sourceset scans the whole service since it's the only module that depends on all four).
 *
 * <p>Package convention: {@code <basePackage>.dom}, {@code .app}, {@code .infra}, {@code .ifc}
 * — note "ifc", not "if", since {@code if} is a reserved Java keyword and cannot be a package
 * segment even though the Gradle module is named {@code -if-rest} per the plan.
 */
public abstract class LayeredArchitectureTest {

    protected abstract String basePackage();

    /**
     * The access guard the application and interface layers must use before they reach a port marked {@code @GuardedData}: the class
     * name of the port the service defines for it (in the application layer). Null for a service that marks nothing.
     */
    protected String accessGuardType() {
        return null;
    }

    /** The consent guard, required in addition for ports marked D3 or D4. Null for a service with none. */
    protected String consentGuardType() {
        return null;
    }

    @Test
    void noHandlerReachesGuardedDataWithoutTheGuards() {
        JavaClasses importedClasses = new ClassFileImporter().importPackages(basePackage());

        java.util.List<String> violations = GuardedDataRule.violations(importedClasses, basePackage(), accessGuardType(), consentGuardType());

        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty(), "Guarded data is reached without its guards:\n  "
                + String.join("\n  ", violations));
    }

    @Test
    void respectsCleanArchitectureLayering() {
        JavaClasses importedClasses = new ClassFileImporter().importPackages(basePackage());

        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy(basePackage() + ".dom..")
                .layer("Application").definedBy(basePackage() + ".app..")
                .layer("Infrastructure").definedBy(basePackage() + ".infra..")
                .layer("Interfaces").definedBy(basePackage() + ".ifc..")
                .whereLayer("Domain").mayNotAccessAnyLayer()
                .whereLayer("Application").mayOnlyAccessLayers("Domain")
                .whereLayer("Infrastructure").mayOnlyAccessLayers("Domain", "Application")
                .whereLayer("Interfaces").mayOnlyAccessLayers("Domain", "Application")
                .check(importedClasses);
    }
}
