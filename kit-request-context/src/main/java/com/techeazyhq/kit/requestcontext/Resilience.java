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

import com.techeazyhq.kit.web.DependencyUnavailableException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/**
 * What stops a slow or failing dependency from holding the caller (TM-15): a time limit on every attempt, bounded retries with
 * backoff for calls that are safe to repeat, and a circuit breaker per host.
 *
 * <ul>
 *   <li>An attempt that takes longer than {@link OutboundPolicy#attemptTimeout()} is cut. A hung peer is released at the timeout.</li>
 *   <li>A failed attempt (an error, a timeout, or a 502, 503 or 504 answer) is retried up to {@code maxRetries} times, after a doubling
 *       backoff with jitter, <b>only if repeating is safe</b>: GET, HEAD, OPTIONS, PUT and DELETE, and a POST or PATCH that carries
 *       an {@code Idempotency-Key}. Nothing else is ever repeated.</li>
 *   <li>A host that fails {@code breakerFailures} times in a row has its circuit opened: calls to it fail at once with
 *       {@link DependencyUnavailableException} for {@code breakerOpenFor}, then one trial call decides. 4xx answers are the caller's
 *       business and do not count as failures; 5xx answers do.</li>
 *   <li>When the attempts are used up on errors or timeouts the caller gets {@link DependencyUnavailableException}; when they are used
 *       up on a 502, 503 or 504 answer the caller gets that answer.</li>
 * </ul>
 */
public final class Resilience {

    private static final System.Logger LOG = System.getLogger(Resilience.class.getName());
    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PUT, HttpMethod.DELETE);
    private static final Set<Integer> TRANSIENT = Set.of(502, 503, 504);

    private final OutboundPolicy policy;
    private final LongSupplier nanoClock;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public Resilience(OutboundPolicy policy) {
        this(policy, System::nanoTime);
    }

    Resilience(OutboundPolicy policy, LongSupplier nanoClock) {
        this.policy = policy;
        this.nanoClock = nanoClock;
    }

    /** The circuit of a host, for a health indicator or a test. */
    public CircuitBreaker breaker(String hostAndPort) {
        return breakers.computeIfAbsent(hostAndPort, k -> new CircuitBreaker(policy.breakerFailures(), policy.breakerOpenFor(), nanoClock));
    }

    public ExchangeFilterFunction filter() {
        return (request, next) -> attempt(request, next, 0);
    }

    private Mono<ClientResponse> attempt(ClientRequest request, org.springframework.web.reactive.function.client.ExchangeFunction next,
                                         int attemptNumber) {
        String host = request.url().getHost() + ":" + request.url().getPort();
        CircuitBreaker breaker = breaker(host);
        Duration wait = breaker.permit();
        if (!wait.isZero()) {
            return Mono.error(new DependencyUnavailableException(host, wait, null));
        }
        boolean mayRepeat = repeatable(request) && attemptNumber < policy.maxRetries();
        return Mono.defer(() -> next.exchange(request)).timeout(policy.attemptTimeout())
                .flatMap(response -> {
                    int status = response.statusCode().value();
                    if (status >= 500) {
                        CircuitBreaker.State before = breaker.state();
                        breaker.recordFailure();
                        logOpened(before, breaker, host);
                        if (mayRepeat && TRANSIENT.contains(status) && breaker.state() != CircuitBreaker.State.OPEN) {
                            return response.releaseBody().then(Mono.defer(() -> pause(attemptNumber).then(attempt(request, next, attemptNumber + 1))));
                        }
                    } else {
                        breaker.recordSuccess();
                    }
                    return Mono.just(response);
                })
                .onErrorResume(failure -> {
                    if (failure instanceof DependencyUnavailableException) {
                        return Mono.error(failure);
                    }
                    CircuitBreaker.State before = breaker.state();
                    breaker.recordFailure();
                    logOpened(before, breaker, host);
                    if (mayRepeat && breaker.state() != CircuitBreaker.State.OPEN) {
                        return pause(attemptNumber).then(Mono.defer(() -> attempt(request, next, attemptNumber + 1)));
                    }
                    return Mono.error(new DependencyUnavailableException(host, Duration.ofSeconds(1), failure));
                });
    }

    private static boolean repeatable(ClientRequest request) {
        return SAFE.contains(request.method()) || request.headers().getFirst("Idempotency-Key") != null;
    }

    /** Doubling, capped, with jitter between half and all of the wait, so retries from many callers do not arrive together. */
    private Mono<Long> pause(int attemptNumber) {
        long base = Math.min(policy.maxBackoff().toMillis(), policy.backoff().toMillis() << Math.min(attemptNumber, 20));
        long jittered = base / 2 + ThreadLocalRandom.current().nextLong(Math.max(1, base / 2 + 1));
        return Mono.delay(Duration.ofMillis(Math.max(1, jittered)));
    }

    private static void logOpened(CircuitBreaker.State before, CircuitBreaker breaker, String host) {
        if (before != CircuitBreaker.State.OPEN && breaker.state() == CircuitBreaker.State.OPEN) {
            LOG.log(System.Logger.Level.WARNING, "circuit to {0} is open: calls to it fail at once for a while", host);
        }
    }
}
