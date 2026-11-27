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

package com.techeazyhq.kit.consent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazyhq.kit.consent.PurposeCode;
import com.techeazyhq.kit.web.ErrorDictionary;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Purpose of use is required, validated and handed on, and a request without a good one never reaches its handler. */
class PurposeOfUseWebFilterTest {

    /** A product's closed list of purposes. */
    private static final List<String> CATALOGUE = List.of("CARE_RECORD", "CLAIMS_PROCESSING", "NOTIFICATIONS_SMS");
    private static final PurposeCatalogue KNOWN = code -> CATALOGUE.contains(code.value());

    private final PurposeOfUseWebFilter filter = new PurposeOfUseWebFilter(KNOWN);
    private final AtomicInteger reached = new AtomicInteger();
    private final WebFilterChain chain = exchange -> {
        reached.incrementAndGet();
        return Mono.empty();
    };

    private MockServerWebExchange run(PurposeOfUseWebFilter f, String path, String... purposeHeaders) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path).header("X-Correlation-Id", "corr-7");
        for (String value : purposeHeaders) {
            request.header(PurposeOfUseWebFilter.HEADER, value);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        f.filter(exchange, chain).block();
        return exchange;
    }

    private JsonNode problem(MockServerWebExchange exchange) throws Exception {
        return new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void aValidPurposeGoesThroughAndIsOnTheExchangeForTheRestOfTheChain() {
        MockServerWebExchange exchange = run(filter, "/api/patients/1", "CARE_RECORD");

        assertEquals(1, reached.get());
        assertEquals(PurposeCode.of("CARE_RECORD"), exchange.getAttribute(PurposeOfUseWebFilter.ATTRIBUTE));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void everyPurposeInTheCatalogueIsAccepted() {
        for (String purpose : CATALOGUE) {
            MockServerWebExchange exchange = run(filter, "/api/x", purpose);

            assertEquals(PurposeCode.of(purpose), exchange.getAttribute(PurposeOfUseWebFilter.ATTRIBUTE));
        }
        assertEquals(CATALOGUE.size(), reached.get());
    }

    @Test
    void aMissingPurposeIsAProblemFromTheDictionaryAndTheHandlerNeverRuns() throws Exception {
        MockServerWebExchange exchange = run(filter, "/api/patients/1");

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.getResponse().getHeaders().getContentType());
        JsonNode body = problem(exchange);
        assertEquals("purpose-required", body.get("code").asText());
        assertEquals(ErrorDictionary.instance().urnPrefix() + "purpose-required", body.get("type").asText());
        assertEquals("corr-7", body.get("correlationId").asText());
        assertEquals(0, reached.get());
    }

    @Test
    void aPurposeThatIsRepeatedMalformedOrUnknownIsInvalid() throws Exception {
        List<String[]> bad = List.of(new String[] {"CARE_RECORD", "CLAIMS_PROCESSING"}, new String[] {"CARE_RECORD", "CARE_RECORD"},
                new String[] {""}, new String[] {"care_record"}, new String[] {"Care Record"}, new String[] {"MARKETING"},
                new String[] {"CARE_RECORD "}, new String[] {"CARE_RECORD,CLAIMS_PROCESSING"}, new String[] {"CARE_RECORD\r\nX-Evil: 1"});

        for (String[] values : bad) {
            MockServerWebExchange exchange = run(filter, "/api/x", values);

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode(), String.join("|", values));
            assertEquals("purpose-invalid", problem(exchange).get("code").asText());
        }
        assertEquals(0, reached.get());
    }

    @Test
    void aWellFormedCodeTheProductDoesNotHaveIsInvalidAndTheCatalogueIsNotAskedAboutText() throws Exception {
        java.util.List<String> asked = new java.util.ArrayList<>();
        PurposeOfUseWebFilter spy = new PurposeOfUseWebFilter(code -> {
            asked.add(code.value());
            return false;
        });

        MockServerWebExchange wellFormed = run(spy, "/api/x", "MARKETING");
        run(spy, "/api/x", "not a code");

        assertEquals("purpose-invalid", problem(wellFormed).get("code").asText());
        assertEquals(List.of("MARKETING"), asked, "only a well-formed code reaches the catalogue");
    }

    @Test
    void theCatalogueIsRequired() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new PurposeOfUseWebFilter(null));
    }

    @Test
    void theAnswerNeverEchoesWhatTheCallerSent() throws Exception {
        MockServerWebExchange exchange = run(filter, "/api/x", "<script>alert(1)</script>");

        assertFalse(exchange.getResponse().getBodyAsString().block().contains("script"));
    }

    @Test
    void thePathsThatAreNotProtectedNeedNoPurpose() {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/", "/other"}) {
            run(filter, path);
        }

        assertEquals(4, reached.get());
    }

    @Test
    void protectionIsEverythingUnderApiByDefaultSoANewEndpointIsProtectedUntilNamedExempt() {
        assertEquals(List.of("/api/**"), PurposeOfUseWebFilter.DEFAULT_PROTECTED_PATHS);
        for (String path : new String[] {"/api", "/api/a", "/api/a/b/c", "/api/a/b?x=1"}) {
            assertEquals(HttpStatus.BAD_REQUEST, run(filter, path).getResponse().getStatusCode(), path);
        }
    }

    @Test
    void theProtectedPathsCanBeNarrowedExplicitly() {
        PurposeOfUseWebFilter narrow = new PurposeOfUseWebFilter(List.of("/api/patients/**", " /api/rx/** ", ""), KNOWN);

        assertEquals(HttpStatus.BAD_REQUEST, run(narrow, "/api/rx/1").getResponse().getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, run(narrow, "/api/patients/1/records").getResponse().getStatusCode());
        assertNull(run(narrow, "/api/reference-data/countries").getResponse().getStatusCode());
    }

    @Test
    void itRunsAfterSecurityAndBeforeTheRequestContext() {
        assertEquals(-50, filter.getOrder());
    }

    @Test
    void theCodesItRaisesAreInTheDictionaryItShips() {
        assertTrue(ErrorDictionary.instance().find("purpose-required").isPresent());
        assertTrue(ErrorDictionary.instance().find("purpose-invalid").isPresent());
        assertEquals(400, ErrorDictionary.instance().status("purpose-required", 0));
    }
}
