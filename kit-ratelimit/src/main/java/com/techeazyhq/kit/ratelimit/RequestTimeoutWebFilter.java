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

import com.techeazyhq.kit.web.Problems;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives every request a time budget (TM-14): one that is not finished in time is cut and answered 503 {@code request-timeout} with
 * {@code Retry-After}, so a slow dependency or a stuck handler cannot hold a connection and its resources for ever. A response that has
 * already started is not rewritten. Streaming routes (server-sent events) are named in {@code exemptPrefixes}: a stream is not
 * bounded by this budget and must bound itself.
 */
public final class RequestTimeoutWebFilter implements WebFilter, Ordered {

    /** Outside the rate limit, inside security. */
    public static final int ORDER = 20;

    private final Duration budget;
    private final List<String> exemptPrefixes;

    public RequestTimeoutWebFilter(Duration budget, List<String> exemptPrefixes) {
        this.budget = Objects.requireNonNull(budget, "budget");
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("the budget is positive: no request is allowed to take for ever");
        }
        this.exemptPrefixes = List.copyOf(exemptPrefixes);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (exemptPrefixes.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange).timeout(budget).onErrorResume(TimeoutException.class, e -> {
            if (exchange.getResponse().isCommitted()) {
                return Mono.error(e);
            }
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
            DataBuffer body = exchange.getResponse().bufferFactory().wrap(Problems.json("request-timeout", 503,
                    "The request took too long. Check the result before you repeat a change.", Problems.correlationId(exchange)));
            return exchange.getResponse().writeWith(Mono.just(body));
        });
    }
}
