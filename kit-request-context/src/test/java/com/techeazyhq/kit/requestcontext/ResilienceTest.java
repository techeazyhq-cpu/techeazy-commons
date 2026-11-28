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

package com.techeazyhq.kit.requestcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techeazyhq.kit.web.DependencyUnavailableException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** A hung peer is released at the timeout, only what is safe is repeated, and an open circuit fails at once (TM-15). */
class ResilienceTest {

    private static final OutboundPolicy POLICY = new OutboundPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 2, Duration.ofMillis(100),
            Duration.ofSeconds(1), 3, Duration.ofSeconds(30));

    private final AtomicInteger calls = new AtomicInteger();

    private WebClient client(Resilience resilience, Function<ClientRequest, Mono<ClientResponse>> peer) {
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            return peer.apply(request);
        };
        return WebClient.builder().exchangeFunction(exchange).filter(resilience.filter()).build();
    }

    private static Mono<ClientResponse> status(HttpStatus status) {
        return Mono.just(ClientResponse.create(status).build());
    }

    @Test
    void aHungPeerIsReleasedAtTheTimeoutAndTheCallerGetsDependencyUnavailable() {
        WebClient client = client(new Resilience(POLICY), request -> Mono.never());

        StepVerifier.withVirtualTime(() -> client.post().uri("https://slow.internal/x").retrieve().toBodilessEntity())
                .thenAwait(Duration.ofSeconds(3))
                .expectErrorSatisfies(e -> assertInstanceOf(DependencyUnavailableException.class, e))
                .verify(Duration.ofSeconds(5));

        assertEquals(1, calls.get(), "a POST with no idempotency key is not repeated: one attempt, cut at 2 s");
    }

    @Test
    void aSafeCallThatHangsIsRetriedAFixedNumberOfTimesAndThenGivesUpInBoundedTime() {
        WebClient client = client(new Resilience(POLICY), request -> Mono.never());

        StepVerifier.withVirtualTime(() -> client.get().uri("https://slow.internal/x").retrieve().toBodilessEntity())
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorSatisfies(e -> assertInstanceOf(DependencyUnavailableException.class, e))
                .verify(Duration.ofSeconds(5));

        assertEquals(3, calls.get(), "the first attempt and two retries, no more");
    }

    @Test
    void aTransientAnswerIsRetriedUntilItSucceeds() {
        AtomicInteger seen = new AtomicInteger();
        WebClient client = client(new Resilience(POLICY), request -> seen.incrementAndGet() < 3 ? status(HttpStatus.SERVICE_UNAVAILABLE)
                : status(HttpStatus.OK));

        StepVerifier.withVirtualTime(() -> client.get().uri("https://flaky.internal/x").retrieve().toBodilessEntity())
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(entity -> assertEquals(HttpStatus.OK, entity.getStatusCode())).verifyComplete();

        assertEquals(3, calls.get());
    }

    @Test
    void whenTheRetriesAreUsedUpOnATransientAnswerTheCallerGetsThatAnswer() {
        WebClient client = client(new Resilience(POLICY), request -> status(HttpStatus.BAD_GATEWAY));

        StepVerifier.withVirtualTime(() -> client.get().uri("https://down.internal/x").exchangeToMono(r -> Mono.just(r.statusCode())))
                .thenAwait(Duration.ofSeconds(5)).expectNext(HttpStatus.BAD_GATEWAY).verifyComplete();

        assertEquals(3, calls.get());
    }

    @Test
    void aWriteIsRepeatedOnlyWhenItCarriesAnIdempotencyKey() {
        WebClient without = client(new Resilience(POLICY), request -> status(HttpStatus.SERVICE_UNAVAILABLE));
        StepVerifier.withVirtualTime(() -> without.post().uri("https://x.internal/pay").exchangeToMono(r -> Mono.just(r.statusCode())))
                .thenAwait(Duration.ofSeconds(5)).expectNext(HttpStatus.SERVICE_UNAVAILABLE).verifyComplete();
        assertEquals(1, calls.get(), "a payment is never sent twice by accident");

        calls.set(0);
        WebClient with = client(new Resilience(POLICY), request -> status(HttpStatus.SERVICE_UNAVAILABLE));
        StepVerifier.withVirtualTime(() -> with.post().uri("https://x.internal/pay").header("Idempotency-Key", "k-1")
                        .exchangeToMono(r -> Mono.just(r.statusCode())))
                .thenAwait(Duration.ofSeconds(5)).expectNext(HttpStatus.SERVICE_UNAVAILABLE).verifyComplete();
        assertEquals(3, calls.get(), "with a key the other side does it once, so it may be repeated");
    }

    @Test
    void aRefusalOfTheCallerIsNotRetriedAndIsNotAFailureOfTheDependency() {
        Resilience resilience = new Resilience(POLICY);
        WebClient client = client(resilience, request -> status(HttpStatus.NOT_FOUND));

        for (int i = 0; i < 10; i++) {
            client.get().uri("https://ok.internal/x").exchangeToMono(r -> Mono.just(r.statusCode())).block();
        }

        assertEquals(10, calls.get(), "no retries");
        assertEquals(CircuitBreaker.State.CLOSED, resilience.breaker("ok.internal:-1").state());
    }

    @Test
    void aHostThatKeepsFailingHasItsCircuitOpenedAndThenCallsFailAtOnceWithoutTouchingIt() {
        Resilience resilience = new Resilience(new OutboundPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 0, Duration.ofMillis(100),
                Duration.ofSeconds(1), 3, Duration.ofSeconds(30)));
        WebClient client = client(resilience, request -> Mono.error(new IllegalStateException("connection refused")));
        for (int i = 0; i < 3; i++) {
            assertThrows(DependencyUnavailableException.class, () -> client.get().uri("https://dead.internal/x").retrieve().toBodilessEntity().block());
        }
        int before = calls.get();

        DependencyUnavailableException failure = assertThrows(DependencyUnavailableException.class,
                () -> client.get().uri("https://dead.internal/x").retrieve().toBodilessEntity().block());

        assertEquals(before, calls.get(), "the open circuit never reached the dependency");
        assertTrue(failure.retryAfter().compareTo(Duration.ofSeconds(25)) > 0, "and says how long to wait");
    }

    @Test
    void theCircuitOfOneHostDoesNotOpenForAnother() {
        Resilience resilience = new Resilience(new OutboundPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 0, Duration.ofMillis(100),
                Duration.ofSeconds(1), 2, Duration.ofSeconds(30)));
        WebClient client = client(resilience, request -> request.url().getHost().equals("dead.internal")
                ? Mono.error(new IllegalStateException("refused")) : status(HttpStatus.OK));
        for (int i = 0; i < 2; i++) {
            assertThrows(DependencyUnavailableException.class, () -> client.get().uri("https://dead.internal/x").retrieve().toBodilessEntity().block());
        }

        assertEquals(HttpStatus.OK, client.get().uri("https://healthy.internal/x").retrieve().toBodilessEntity().block().getStatusCode());
    }

    @Test
    void afterTheOpenTimeOneTrialDecidesWhetherTheCircuitCloses() {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        Resilience resilience = new Resilience(new OutboundPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 0, Duration.ofMillis(100),
                Duration.ofSeconds(1), 2, Duration.ofSeconds(30)), nanos::get);
        boolean[] up = {false};
        WebClient client = client(resilience, request -> up[0] ? status(HttpStatus.OK) : Mono.error(new IllegalStateException("refused")));
        for (int i = 0; i < 2; i++) {
            assertThrows(DependencyUnavailableException.class, () -> client.get().uri("https://back.internal/x").retrieve().toBodilessEntity().block());
        }
        assertThrows(DependencyUnavailableException.class, () -> client.get().uri("https://back.internal/x").retrieve().toBodilessEntity().block());

        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        up[0] = true;

        assertEquals(HttpStatus.OK, client.get().uri("https://back.internal/x").retrieve().toBodilessEntity().block().getStatusCode());
        assertEquals(CircuitBreaker.State.CLOSED, resilience.breaker("back.internal:-1").state());
    }

    @Test
    void thePolicyHasNoZeroTimeoutAndNoEndlessRetries() {
        assertThrows(IllegalArgumentException.class, () -> new OutboundPolicy(Duration.ZERO, Duration.ofSeconds(1), 1, Duration.ofMillis(1),
                Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new OutboundPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 6, Duration.ofMillis(1),
                Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new OutboundPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 1, Duration.ofMillis(1),
                Duration.ofSeconds(1), 0, Duration.ofSeconds(1)));
        assertEquals(Set.of(2, 5), Set.of(OutboundPolicy.defaults().maxRetries(), OutboundPolicy.defaults().breakerFailures()));
    }
}
