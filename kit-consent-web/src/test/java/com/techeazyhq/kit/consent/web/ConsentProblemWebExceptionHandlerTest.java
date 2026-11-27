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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazyhq.kit.consent.ConsentRefusedException;
import com.techeazyhq.kit.consent.ConsentUnavailableException;
import com.techeazyhq.kit.web.ErrorDictionary;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** A consent stop is a problem from the dictionary: 403 when consent says no, a retryable 503 when nobody could say. */
class ConsentProblemWebExceptionHandlerTest {

    private final ConsentProblemWebExceptionHandler handler = new ConsentProblemWebExceptionHandler();

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/x").header("X-Correlation-Id", "corr-5"));
    }

    private static JsonNode body(MockServerWebExchange exchange) throws Exception {
        return new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void aRefusalIs403ConsentDeniedWithTheCorrelationIdAndNoReason() throws Exception {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new ConsentRefusedException("consent-withdrawn")).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.getResponse().getHeaders().getContentType());
        JsonNode problem = body(exchange);
        assertEquals("consent-denied", problem.get("code").asText());
        assertEquals(ErrorDictionary.instance().urnPrefix() + "consent-denied", problem.get("type").asText());
        assertEquals("corr-5", problem.get("correlationId").asText());
        assertFalse(problem.toString().contains("withdrawn"), "the reason code stays inside");
        assertNull(exchange.getResponse().getHeaders().getFirst("Retry-After"));
    }

    @Test
    void anUnavailableCheckIsARetryable503WithRetryAfterAndItIsNotARefusal() throws Exception {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new ConsentUnavailableException(Duration.ofSeconds(20))).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("20", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        JsonNode problem = body(exchange);
        assertEquals("consent-unavailable", problem.get("code").asText());
        assertTrue(problem.get("retryable").asBoolean());
        assertFalse(problem.get("detail").asText().toLowerCase().contains("denied"), "it must not read as a refusal");
        assertEquals("corr-5", problem.get("correlationId").asText());
    }

    @Test
    void retryAfterIsWholeSecondsRoundedUpAndAtLeastOne() {
        for (long[] c : new long[][] {{1, 1}, {1500, 2}, {2000, 2}, {2001, 3}, {900_000, 900}}) {
            MockServerWebExchange exchange = exchange();

            handler.handle(exchange, new ConsentUnavailableException(Duration.ofMillis(c[0]))).block();

            assertEquals(String.valueOf(c[1]), exchange.getResponse().getHeaders().getFirst("Retry-After"), c[0] + " ms");
        }
    }

    @Test
    void anythingElseIsPassedOnUntouched() {
        MockServerWebExchange exchange = exchange();
        IllegalStateException other = new IllegalStateException("not ours");

        Throwable passed = assertThrows(Throwable.class, () -> handler.handle(exchange, other).block());

        assertEquals(other, passed);
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void aResponseAlreadyOnItsWayIsLeftAlone() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block();
        ConsentRefusedException refused = new ConsentRefusedException("no-consent");

        assertEquals(refused, assertThrows(Throwable.class, () -> handler.handle(exchange, refused).block()));
    }

    @Test
    void itRunsAheadOfTheSharedFallbackHandler() {
        assertTrue(handler.getOrder() < -2);
    }

    @Test
    void bothCodesAreInTheDictionaryWithTheStatusesTheHandlerSends() {
        assertEquals(403, ErrorDictionary.instance().status("consent-denied", 0));
        assertEquals(503, ErrorDictionary.instance().status("consent-unavailable", 0));
        assertTrue(ErrorDictionary.instance().retryable("consent-unavailable", 503));
        assertFalse(ErrorDictionary.instance().retryable("consent-denied", 403));
    }
}
