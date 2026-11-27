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

import com.techeazyhq.kit.consent.ConsentRefusedException;
import com.techeazyhq.kit.consent.ConsentUnavailableException;
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
 * Answers what {@link com.techeazyhq.kit.consent.ConsentGuard} stops a request with (EP-09-08), as problems from the
 * error dictionary:
 * <ul>
 *   <li>{@link ConsentRefusedException}: 403 {@code consent-denied};</li>
 *   <li>{@link ConsentUnavailableException}: 503 {@code consent-unavailable}, retryable, with {@code Retry-After}. It is not
 *       a refusal, so it says nothing that suggests the person said no, and it never invites a new consent request.</li>
 * </ul>
 * The answer is a fixed sentence and the correlation id. It never carries the reason code, the subject or the purpose.
 * Anything else is passed on.
 */
public final class ConsentProblemWebExceptionHandler implements WebExceptionHandler, Ordered {

    /** Ahead of the shared fallback handler (-2), which would otherwise answer a consent stop as an internal error. */
    @Override
    public int getOrder() {
        return -3;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        if (ex instanceof ConsentRefusedException) {
            return write(exchange, HttpStatus.FORBIDDEN, Problems.json("consent-denied", 403,
                    "Consent does not allow this.", Problems.correlationId(exchange)), null);
        }
        if (ex instanceof ConsentUnavailableException unavailable) {
            long seconds = Math.max(1, (unavailable.retryAfter().toMillis() + 999) / 1000);
            return write(exchange, HttpStatus.SERVICE_UNAVAILABLE, Problems.json("consent-unavailable", 503,
                    "Consent could not be checked right now. Nothing was done. Try again later.",
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
