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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What no one else answers (the error-dictionary standard), as the same {@code application/problem+json} instead of Spring's default error
 * JSON, which carries a timestamp and an internal request id and varies by version:
 * <ul>
 *   <li>input that could not be read (bad JSON, a parameter of the wrong type): 400 {@code malformed-request};</li>
 *   <li>input the database refuses for what it contains (a NUL character, text it cannot encode, a value too long or out of range,
 *       a malformed number or date): also 400 {@code malformed-request}, since the caller's text is the fault, not the platform's;</li>
 *   <li>a body over the limit the codecs allow: 413 {@code payload-too-large};</li>
 *   <li>an exception nobody expected: 500 {@code internal-error}, with nothing about it in the answer, and the exception
 *       logged as {@code [TZ-INTERNAL-ERROR]} with the correlation id the caller was given;</li>
 *   <li>a request nobody can serve, as raised by the router and the codecs: 404 {@code not-found} (unknown route), 405
 *       {@code method-not-allowed} (with its Allow header), 406 {@code not-acceptable}, 415 {@code unsupported-media-type}.</li>
 * </ul>
 * It leaves alone what is already someone's to answer: other {@code ResponseStatusException}s, Spring Security's exceptions, a response already on its way, and a client that went away.
 * Ordered after the handlers for {@link DomRuleViolation} and dependency outages, and ahead of Spring's default.
 */
@Order(-2)
public class FallbackProblemWebExceptionHandler implements WebExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackProblemWebExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public FallbackProblemWebExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        if (ex instanceof ResponseStatusException rse && ROUTING_CODES.containsKey(rse.getStatusCode().value())) {
            return routingProblem(exchange, rse);
        }
        if (tooLarge(ex)) {
            return write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, Problems.body("payload-too-large", 413,
                    "The request body is larger than this service accepts.", Problems.correlationId(exchange)));
        }
        if (isNotOurs(ex)) {
            return Mono.error(ex);
        }
        if (ex instanceof ServerWebInputException || refusedAsInput(ex)) {
            return write(exchange, HttpStatus.BAD_REQUEST, Problems.body("malformed-request", 400,
                    "The request could not be read: check its body, its parameters and its headers.",
                    Problems.correlationId(exchange)));
        }
        LOG.error("[TZ-INTERNAL-ERROR] {} {} ended with an unexpected error (correlationId {})",
                exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(),
                Problems.correlationId(exchange), ex);
        return write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, Problems.body("internal-error", 500,
                "Nothing more is shown here. Quote the correlation id if you report it.", Problems.correlationId(exchange)));
    }

    /** The answers the router and the codecs give for a request nobody can serve, as dictionary codes. */
    private static final Map<Integer, String> ROUTING_CODES = Map.of(
            404, "not-found", 405, "method-not-allowed", 406, "not-acceptable", 415, "unsupported-media-type");

    private Mono<Void> routingProblem(ServerWebExchange exchange, ResponseStatusException rse) {
        int status = rse.getStatusCode().value();
        // headers the exception carries, such as Allow on a 405 or Accept on a 415, tell the caller what would work
        rse.getHeaders().forEach((name, values) -> exchange.getResponse().getHeaders().addAll(name, values));
        return write(exchange, HttpStatus.valueOf(status), Problems.body(ROUTING_CODES.get(status), status, null,
                Problems.correlationId(exchange)));
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, Map<String, Object> problem) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(problem))
                .map(bytes -> response.bufferFactory().wrap(bytes))
                .flatMap(buffer -> response.writeWith(Mono.just((DataBuffer) buffer)));
    }

    /**
     * SQLSTATEs by which a database says the value it was given is the problem: invalid byte sequence (a NUL character), an
     * untranslatable character, a malformed text representation, a string too long, a number or a date out of range.
     */
    private static final Set<String> INPUT_SQL_STATES = Set.of("22021", "22P05", "22P02", "22001", "22003", "22007", "22008");

    /**
     * Whether a database refused what the caller sent, from the SQLSTATE of any exception in the cause chain. It asks by name
     * (an R2DBC exception's {@code getSqlState()}) so this library needs no database driver, and the gateway, which has none,
     * is unaffected.
     */
    /** A body over the codec's limit (spring.codec.max-in-memory-size), however many layers wrapped it. */
    static boolean tooLarge(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.core.io.buffer.DataBufferLimitException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    static boolean refusedAsInput(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String state = sqlState(t);
            if (state != null && INPUT_SQL_STATES.contains(state)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String sqlState(Throwable t) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                continue; // a driver's own nested exception types may be package-private: ask one of its public parents
            }
            try {
                Method m = c.getMethod("getSqlState");
                if (m.getReturnType() == String.class) {
                    return (String) m.invoke(t);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // not this class
            }
        }
        return null;
    }

    private static boolean isNotOurs(Throwable ex) {
        if (ex instanceof ServerWebInputException) {
            return false;
        }
        String type = ex.getClass().getName();
        return ex instanceof ResponseStatusException
                || type.startsWith("org.springframework.security.")
                || ex instanceof java.util.concurrent.CancellationException
                // a client that went away is not a fault of ours
                || type.endsWith("AbortedException") || type.endsWith("ClientAbortException")
                || (ex.getMessage() != null && ex.getMessage().contains("Connection reset by peer"));
    }
}
