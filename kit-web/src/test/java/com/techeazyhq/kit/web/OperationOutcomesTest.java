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

package com.techeazyhq.kit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FHIR endpoints answer the same stable code as REST (STD-ERR-07). */
class OperationOutcomesTest {

    @SuppressWarnings("unchecked")
    @Test
    void anOperationOutcomeCarriesTheFhirIssueTypeAndTheOghCode() {
        Map<String, Object> outcome = OperationOutcomes.of("demo-thing-not-found", "No item i1", "corr-9");
        assertEquals("OperationOutcome", outcome.get("resourceType"));
        Map<String, Object> issue = ((List<Map<String, Object>>) outcome.get("issue")).get(0);
        assertEquals("error", issue.get("severity"));
        assertEquals("not-found", issue.get("code"));
        assertEquals("No item i1", issue.get("diagnostics"));
        Map<String, Object> coding = ((List<Map<String, Object>>) ((Map<String, Object>) issue.get("details")).get("coding")).get(0);
        assertEquals("urn:demo:errors", coding.get("system"));
        assertEquals("demo-thing-not-found", coding.get("code"));
        assertEquals("Thing not found", coding.get("display"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aMissingDetailIsLeftOut() {
        Map<String, Object> issue = ((List<Map<String, Object>>) OperationOutcomes.of("quota-exceeded", null, null).get("issue")).get(0);
        assertFalse(issue.containsKey("diagnostics"));
        assertEquals("throttled", issue.get("code"));
    }
}
