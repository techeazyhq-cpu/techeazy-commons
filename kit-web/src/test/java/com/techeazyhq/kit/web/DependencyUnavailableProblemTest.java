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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class DependencyUnavailableProblemTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aDependencyThatCannotBeUsedIsARetryable503WithRetryAfterAndNamesNothing() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x").header("X-Correlation-Id", "corr-3"));

        new FallbackProblemWebExceptionHandler(JSON).handle(exchange, new DependencyUnavailableException("payments.internal:443",
                Duration.ofMillis(1500), new IllegalStateException("refused"))).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("2", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        Map<?, ?> problem = JSON.readValue(exchange.getResponse().getBodyAsString().block(), Map.class);
        assertEquals("dependency-unavailable", problem.get("code"));
        assertEquals(true, problem.get("retryable"));
        assertEquals("corr-3", problem.get("correlationId"));
        assertFalse(problem.toString().contains("payments") || problem.toString().contains("refused"));
    }

    @Test
    void itIsFoundHoweverDeepItIsWrapped() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x"));

        new FallbackProblemWebExceptionHandler(JSON).handle(exchange, new RuntimeException("outer",
                new DependencyUnavailableException("x", Duration.ofSeconds(5), null))).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("5", exchange.getResponse().getHeaders().getFirst("Retry-After"));
    }
}
