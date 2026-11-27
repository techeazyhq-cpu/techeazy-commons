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

import com.techeazyhq.kit.consent.PurposeCode;
import com.techeazyhq.kit.web.Problems;
import java.util.List;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Purpose of use on every request that touches personal data. A request to a protected path must carry exactly one
 * {@code X-Purpose-Of-Use} header with a code from the product's {@link PurposeCatalogue}; anything else is a 400 problem from
 * the error dictionary ({@code purpose-required}, {@code purpose-invalid}) and the handler never runs. The validated
 * {@link PurposeCode} is then on the exchange ({@link #ATTRIBUTE}) for the request context, the consent check and the audit.
 *
 * <p>Fail closed: the protected paths default to everything under {@code /api}, so a new endpoint is protected until someone
 * names it exempt. Runs after Spring Security, so an anonymous caller gets 401 first. The answer never echoes what the caller
 * sent.
 */
public final class PurposeOfUseWebFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Purpose-Of-Use";

    /** The exchange attribute that holds the validated {@link PurposeCode}. */
    public static final String ATTRIBUTE = "techeazy.purposeOfUse";

    /** What is protected when nothing else is said. */
    public static final List<String> DEFAULT_PROTECTED_PATHS = List.of("/api/**");

    private final List<PathPattern> protectedPaths;
    private final PurposeCatalogue catalogue;

    public PurposeOfUseWebFilter(List<String> protectedPathPatterns, PurposeCatalogue catalogue) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.protectedPaths = protectedPathPatterns.stream().map(String::strip).filter(p -> !p.isEmpty()).map(parser::parse).toList();
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    }

    /** Everything under {@code /api} is protected. */
    public PurposeOfUseWebFilter(PurposeCatalogue catalogue) {
        this(DEFAULT_PROTECTED_PATHS, catalogue);
    }

    /** After Spring Security's chain (-100), before a request context filter (0). */
    @Override
    public int getOrder() {
        return -50;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        if (protectedPaths.stream().noneMatch(p -> p.matches(path))) {
            return chain.filter(exchange);
        }
        List<String> values = exchange.getRequest().getHeaders().get(HEADER);
        if (values == null || values.isEmpty()) {
            return refuse(exchange, Problems.json("purpose-required", 400, "The request carries no " + HEADER + " header.",
                    Problems.correlationId(exchange)));
        }
        if (values.size() != 1) {
            return refuse(exchange, Problems.json("purpose-invalid", 400, HEADER + " must appear exactly once.",
                    Problems.correlationId(exchange)));
        }
        PurposeCode purpose = parse(values.get(0));
        if (purpose == null || !catalogue.contains(purpose)) {
            return refuse(exchange, Problems.json("purpose-invalid", 400, HEADER + " is not a purpose of this platform.",
                    Problems.correlationId(exchange)));
        }
        exchange.getAttributes().put(ATTRIBUTE, purpose);
        return chain.filter(exchange);
    }

    private static PurposeCode parse(String text) {
        try {
            return PurposeCode.of(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // never echoes the value sent: it is caller text, and the answer is a fixed sentence from this class
    private static Mono<Void> refuse(ServerWebExchange exchange, byte[] body) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
