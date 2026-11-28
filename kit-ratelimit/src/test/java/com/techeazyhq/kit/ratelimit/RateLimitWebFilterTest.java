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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazyhq.kit.claims.Claims;
import com.techeazyhq.kit.requestcontext.RequestContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** One tenant, or one caller, at its limit is a 429 problem and slows no one else. */
class RateLimitWebFilterTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicInteger reached = new AtomicInteger();
    private final WebFilterChain chain = exchange -> {
        reached.incrementAndGet();
        return Mono.empty();
    };

    private RateLimitWebFilter filter(Limit tenant, Limit caller) {
        return new RateLimitWebFilter(new TokenBuckets(nanos::get, 1000), tenant, caller, List.of("/actuator/health"));
    }

    private static RequestContext as(UUID tenant, String subject) {
        return new RequestContext("corr-1", "token", new Claims(subject, tenant, Set.of("staff")));
    }

    private MockServerWebExchange call(RateLimitWebFilter filter, RequestContext context, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).header("X-Correlation-Id", "corr-1"));
        Mono<Void> run = filter.filter(exchange, chain);
        run = context == null ? run : run.contextWrite(c -> c.put(RequestContext.KEY, context));
        run.block();
        return exchange;
    }

    private static boolean refused(MockServerWebExchange exchange) {
        return exchange.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
    }

    @Test
    void requestsUnderTheLimitsPassUntouched() {
        RateLimitWebFilter filter = filter(new Limit(100, 100), new Limit(10, 10));

        for (int i = 0; i < 10; i++) {
            assertFalse(refused(call(filter, as(A, "u1"), "/api/x")));
        }
        assertEquals(10, reached.get());
    }

    @Test
    void aCallerOverItsLimitGetsA429ProblemWithRetryAfterAndTheRequestNeverReachesTheService() throws Exception {
        RateLimitWebFilter filter = filter(new Limit(1000, 1000), new Limit(3, 1));
        for (int i = 0; i < 3; i++) {
            call(filter, as(A, "u1"), "/api/x");
        }

        MockServerWebExchange exchange = call(filter, as(A, "u1"), "/api/x");

        assertTrue(refused(exchange));
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.getResponse().getHeaders().getContentType());
        assertEquals("1", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        JsonNode problem = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertEquals("rate-limit-exceeded", problem.get("code").asText());
        assertEquals("corr-1", problem.get("correlationId").asText());
        assertTrue(problem.get("retryable").asBoolean());
        assertEquals(3, reached.get(), "the refused request did no work");
    }

    @Test
    void oneTenantAtItsLimitDoesNotSlowAnotherTenant() {
        RateLimitWebFilter filter = filter(new Limit(5, 1), new Limit(1000, 1000));
        for (int i = 0; i < 20; i++) {
            call(filter, as(A, "u" + (i % 7)), "/api/x");
        }

        assertTrue(refused(call(filter, as(A, "someone-new"), "/api/x")), "the tenant's allowance is shared by its callers");
        for (int i = 0; i < 5; i++) {
            assertFalse(refused(call(filter, as(B, "u1"), "/api/x")), "tenant B is untouched");
        }
    }

    @Test
    void oneCallerAtItsLimitDoesNotSlowAnotherCallerOfTheSameTenant() {
        RateLimitWebFilter filter = filter(new Limit(1000, 1000), new Limit(2, 1));
        for (int i = 0; i < 10; i++) {
            call(filter, as(A, "noisy"), "/api/x");
        }

        assertTrue(refused(call(filter, as(A, "noisy"), "/api/x")));
        assertFalse(refused(call(filter, as(A, "quiet"), "/api/x")));
    }

    @Test
    void theAnswerDoesNotSayWhichLimitWasHit() throws Exception {
        MockServerWebExchange byCaller = call(filterAtCallerLimit(), as(A, "u1"), "/api/x");
        String body = byCaller.getResponse().getBodyAsString().block();

        assertFalse(body.contains("tenant") || body.contains("caller") || body.contains("u1") || body.contains(A.toString()), body);
    }

    private RateLimitWebFilter filterAtCallerLimit() {
        RateLimitWebFilter filter = filter(new Limit(1000, 1000), new Limit(1, 1));
        call(filter, as(A, "u1"), "/api/x");
        return filter;
    }

    @Test
    void healthProbesAndRequestsWithNoContextAreNotLimited() {
        RateLimitWebFilter filter = filter(new Limit(1, 0.001), new Limit(1, 0.001));

        for (int i = 0; i < 10; i++) {
            assertFalse(refused(call(filter, as(A, "u1"), "/actuator/health/liveness")));
            assertFalse(refused(call(filter, null, "/api/x")));
        }
        assertEquals(20, reached.get());
    }

    @Test
    void tokensComeBackSoACallerIsNotLockedOut() {
        RateLimitWebFilter filter = filter(new Limit(1000, 1000), new Limit(1, 1));
        call(filter, as(A, "u1"), "/api/x");
        assertTrue(refused(call(filter, as(A, "u1"), "/api/x")));

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());

        assertFalse(refused(call(filter, as(A, "u1"), "/api/x")));
        assertNull(null);
    }
}
