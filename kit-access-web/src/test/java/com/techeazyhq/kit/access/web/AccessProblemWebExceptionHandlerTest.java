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

package com.techeazyhq.kit.access.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazyhq.kit.access.AccessDeniedException;
import com.techeazyhq.kit.access.AccessUnavailableException;
import com.techeazyhq.kit.web.ErrorDictionary;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class AccessProblemWebExceptionHandlerTest {

    private final AccessProblemWebExceptionHandler handler = new AccessProblemWebExceptionHandler();

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/x").header("X-Correlation-Id", "corr-8"));
    }

    private static JsonNode body(MockServerWebExchange exchange) throws Exception {
        return new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void aDenyIs403ForbiddenWithTheCorrelationIdAndNoReason() throws Exception {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new AccessDeniedException(List.of("no-care-relationship"))).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.getResponse().getHeaders().getContentType());
        JsonNode problem = body(exchange);
        assertEquals("forbidden", problem.get("code").asText());
        assertEquals("corr-8", problem.get("correlationId").asText());
        assertFalse(problem.toString().contains("relationship"), "the reason stays in the log");
        assertNull(exchange.getResponse().getHeaders().getFirst("Retry-After"));
    }

    @Test
    void anUnavailableDecisionIsARetryable503WithRetryAfterAndDoesNotReadAsARefusal() throws Exception {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new AccessUnavailableException("timeout", Duration.ofSeconds(5))).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("5", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        JsonNode problem = body(exchange);
        assertEquals("access-unavailable", problem.get("code").asText());
        assertEquals(ErrorDictionary.instance().urnPrefix() + "access-unavailable", problem.get("type").asText());
        assertTrue(problem.get("retryable").asBoolean());
        assertFalse(problem.get("detail").asText().toLowerCase().contains("not allowed"));
    }

    @Test
    void anythingElseIsPassedOn() {
        IllegalStateException other = new IllegalStateException("x");

        assertThrows(IllegalStateException.class, () -> handler.handle(exchange(), other).block());
        assertEquals(-3, handler.getOrder());
    }
}
