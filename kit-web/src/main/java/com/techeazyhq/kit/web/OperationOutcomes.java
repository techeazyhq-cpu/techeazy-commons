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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The FHIR counterpart of {@link Problems} (STD-ERR-07): FHIR endpoints answer an {@code OperationOutcome} whose
 * {@code issue.code} is the FHIR issue-type from the error dictionary and whose {@code issue.details.coding} carries the
 * product's code, so a FHIR client and a REST client can branch on the same stable code.
 */
public final class OperationOutcomes {

    private OperationOutcomes() {
    }

    /** An {@code OperationOutcome} for {@code code}. {@code detail} and {@code correlationId} may be null. */
    public static Map<String, Object> of(String code, String detail, String correlationId) {
        ErrorDictionary dictionary = ErrorDictionary.instance();
        Map<String, Object> coding = new LinkedHashMap<>();
        coding.put("system", dictionary.errorSystem());
        coding.put("code", code);
        coding.put("display", dictionary.title(code));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("coding", List.of(coding));
        details.put("text", dictionary.title(code));

        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", "error");
        issue.put("code", dictionary.fhirIssueType(code));
        issue.put("details", details);
        if (detail != null && !detail.isBlank()) {
            issue.put("diagnostics", detail);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            issue.put("expression", List.of("correlationId=" + correlationId));
        }

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("resourceType", "OperationOutcome");
        outcome.put("issue", List.of(issue));
        return outcome;
    }
}
