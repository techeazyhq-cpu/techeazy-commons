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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rule fails the build for a handler that reaches guarded data without the guards, and passes for one that uses them. */
class GuardedDataRuleTest {

    private static final String GOOD = "com.techeazyhq.kit.test.fixture.good";
    private static final String BAD = "com.techeazyhq.kit.test.fixture.bad";

    private static JavaClasses classes(String base) {
        return new ClassFileImporter().importPackages(base);
    }

    @Test
    void handlersThatUseTheGuardsPassAndAdaptersAndTheCompositionRootAreNotChecked() {
        assertEquals(List.of(), GuardedDataRule.violations(classes(GOOD), GOOD, GOOD + ".app.AccessCheck", GOOD + ".app.ConsentCheck"));
    }

    @Test
    void aHandlerThatUsesNeitherGuardIsAViolation() {
        List<String> found = GuardedDataRule.violations(classes(BAD), BAD, BAD + ".app.AccessCheck", BAD + ".app.ConsentCheck");

        assertTrue(found.stream().anyMatch(v -> v.contains("ForgetsEverything") && v.contains("access guard") && v.contains("consent guard")),
                found.toString());
    }

    @Test
    void personalDataNeedsTheConsentGuardToo() {
        List<String> found = GuardedDataRule.violations(classes(BAD), BAD, BAD + ".app.AccessCheck", BAD + ".app.ConsentCheck");

        assertTrue(found.stream().anyMatch(v -> v.contains("ForgetsConsent") && v.contains("consent guard") && !v.contains("access guard")),
                found.toString());
    }

    @Test
    void anInterfaceClassThatReachesTheStoreIsCheckedToo() {
        List<String> found = GuardedDataRule.violations(classes(BAD), BAD, BAD + ".app.AccessCheck", BAD + ".app.ConsentCheck");

        assertTrue(found.stream().anyMatch(v -> v.contains("DirectHandler")), found.toString());
        assertEquals(3, found.size(), found.toString());
    }

    @Test
    void aServiceThatNamesNoGuardsFailsAsSoonAsItMarksAPort() {
        List<String> found = GuardedDataRule.violations(classes(GOOD), GOOD, null, null);

        assertTrue(found.stream().anyMatch(v -> v.contains("ReadNote")), found.toString());
        assertTrue(found.stream().anyMatch(v -> v.contains("ReadLabel")), found.toString());
    }

    @Test
    void aD2PortNeedsTheAccessGuardOnly() {
        List<String> found = GuardedDataRule.violations(classes(GOOD), GOOD, GOOD + ".app.AccessCheck", null);

        assertTrue(found.stream().noneMatch(v -> v.contains("ReadLabel")), "a D2 port needs no consent guard: " + found);
        assertTrue(found.stream().anyMatch(v -> v.contains("ReadNote") && v.contains("consent guard")), found.toString());
    }
}
