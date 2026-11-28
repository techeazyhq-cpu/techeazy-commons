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

package com.techeazyhq.kit.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestTimeoutWebFilterTest {

    private final RequestTimeoutWebFilter filter = new RequestTimeoutWebFilter(Duration.ofSeconds(5), List.of("/api/stream"));

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).header("X-Correlation-Id", "corr-7"));
    }

    @Test
    void aRequestThatFinishesInTimeIsUntouched() {
        MockServerWebExchange exchange = exchange("/api/x");

        StepVerifier.create(filter.filter(exchange, e -> Mono.delay(Duration.ofMillis(10)).then())).verifyComplete();

        assertEquals(null, exchange.getResponse().getStatusCode());
    }

    @Test
    void aRequestThatNeverFinishesIsCutAnd503RequestTimeoutWithRetryAfter() throws Exception {
        MockServerWebExchange exchange = exchange("/api/x");
        WebFilterChain stuck = e -> Mono.never();

        StepVerifier.withVirtualTime(() -> filter.filter(exchange, stuck)).thenAwait(Duration.ofSeconds(6)).verifyComplete();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.getResponse().getHeaders().getContentType());
        assertEquals("1", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        var problem = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertEquals("request-timeout", problem.get("code").asText());
        assertEquals("corr-7", problem.get("correlationId").asText());
    }

    @Test
    void aStreamingRouteIsNotCut() {
        MockServerWebExchange exchange = exchange("/api/stream/events");

        StepVerifier.withVirtualTime(() -> filter.filter(exchange, e -> Mono.delay(Duration.ofSeconds(60)).then()))
                .thenAwait(Duration.ofSeconds(61)).verifyComplete();

        assertEquals(null, exchange.getResponse().getStatusCode());
    }

    @Test
    void theBudgetIsPositive() {
        assertThrows(IllegalArgumentException.class, () -> new RequestTimeoutWebFilter(Duration.ZERO, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RequestTimeoutWebFilter(Duration.ofSeconds(-1), List.of()));
    }
}
