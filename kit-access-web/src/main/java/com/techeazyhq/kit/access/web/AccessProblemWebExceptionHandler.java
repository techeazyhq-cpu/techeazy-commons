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

import com.techeazyhq.kit.access.AccessDeniedException;
import com.techeazyhq.kit.access.AccessUnavailableException;
import com.techeazyhq.kit.web.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Answers what the PEP stops a request with, as problems from the error dictionary:
 * <ul>
 *   <li>{@link AccessDeniedException}: 403 {@code forbidden}, with a fixed sentence and the correlation id. The reason codes
 *       stay in the log; they would tell an attacker which fact to change.</li>
 *   <li>{@link AccessUnavailableException}: a retryable 503 {@code access-unavailable} with {@code Retry-After}. It is not a
 *       refusal and says nothing that reads like one.</li>
 * </ul>
 * Anything else is passed on.
 */
public final class AccessProblemWebExceptionHandler implements WebExceptionHandler, Ordered {

    /** Ahead of the shared fallback handler (-2), which would answer these as an internal error. */
    @Override
    public int getOrder() {
        return -3;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        if (ex instanceof AccessDeniedException) {
            return write(exchange, HttpStatus.FORBIDDEN, Problems.json("forbidden", 403,
                    "You are not allowed to do this.", Problems.correlationId(exchange)), null);
        }
        if (ex instanceof AccessUnavailableException unavailable) {
            long seconds = Math.max(1, (unavailable.retryAfter().toMillis() + 999) / 1000);
            return write(exchange, HttpStatus.SERVICE_UNAVAILABLE, Problems.json("access-unavailable", 503,
                    "Access could not be checked right now. Nothing was done. Try again later.",
                    Problems.correlationId(exchange)), String.valueOf(seconds));
        }
        return Mono.error(ex);
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, byte[] body, String retryAfterSeconds) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfterSeconds != null) {
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
