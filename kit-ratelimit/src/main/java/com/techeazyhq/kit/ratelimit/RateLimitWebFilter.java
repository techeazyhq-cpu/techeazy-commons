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

import com.techeazyhq.kit.requestcontext.RequestContext;
import com.techeazyhq.kit.web.Problems;
import java.util.List;
import java.util.Objects;
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
 * Limits how fast a tenant and a caller may call (TM-14): every request takes a token from the caller's bucket and from the tenant's.
 * Either empty is a 429 {@code rate-limit-exceeded} problem with {@code Retry-After}, before any work is done. The answer does not say
 * which limit was hit. A tenant at its limit cannot slow another tenant, because the buckets are separate; a caller at its limit does not
 * use up the tenant's allowance beyond the one request that was refused.
 *
 * <p>It runs after authentication and the request context, so the tenant and the caller are the verified ones; a request with no
 * context (health probes, anonymous calls the security chain will refuse) is passed on and left to the edge. Paths in
 * {@code exemptPrefixes} (health) are never limited.
 */
public final class RateLimitWebFilter implements WebFilter, Ordered {

    /** After Spring Security (-100) and the request-context filter (0). */
    public static final int ORDER = 10;

    private final TokenBuckets buckets;
    private final Limit tenantLimit;
    private final Limit callerLimit;
    private final List<String> exemptPrefixes;

    public RateLimitWebFilter(TokenBuckets buckets, Limit tenantLimit, Limit callerLimit, List<String> exemptPrefixes) {
        this.buckets = Objects.requireNonNull(buckets, "buckets");
        this.tenantLimit = Objects.requireNonNull(tenantLimit, "tenantLimit");
        this.callerLimit = Objects.requireNonNull(callerLimit, "callerLimit");
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
        return RequestContext.current().map(java.util.Optional::of).onErrorResume(IllegalStateException.class, e -> Mono.just(java.util.Optional.empty()))
                .flatMap(context -> {
                    if (context.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    RequestContext ctx = context.get();
                    TokenBuckets.Result caller = buckets.tryAcquire("caller:" + ctx.tenantId() + ":" + ctx.claims().subject(), callerLimit);
                    TokenBuckets.Result tenant = caller.allowed() ? buckets.tryAcquire("tenant:" + ctx.tenantId(), tenantLimit) : caller;
                    return tenant.allowed() ? chain.filter(exchange) : refuse(exchange, tenant);
                });
    }

    private static Mono<Void> refuse(ServerWebExchange exchange, TokenBuckets.Result result) {
        long seconds = Math.max(1, (result.retryAfter().toMillis() + 999) / 1000);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        DataBuffer body = exchange.getResponse().bufferFactory().wrap(Problems.json("rate-limit-exceeded", 429,
                "Too many requests. Wait and try again.", Problems.correlationId(exchange)));
        return exchange.getResponse().writeWith(Mono.just(body));
    }
}
