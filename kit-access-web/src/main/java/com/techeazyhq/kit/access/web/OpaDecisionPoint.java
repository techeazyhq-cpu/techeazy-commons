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

import com.techeazyhq.kit.access.Decision;
import com.techeazyhq.kit.access.PolicyDecisionPoint;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Asks an OPA server (usually the sidecar on the same host) for a decision: {@code POST {base}/v1/data/{rule path}} with
 * {@code {"input": ...}}, answer {@code {"result": {...}}}. OPA answers {@code {}} when the rule is undefined, and that is a
 * policy bug, so it is an error here (never an allow). A cleartext {@code http} address is accepted for the loopback only, so
 * a sidecar works and nothing else goes unencrypted; any other host must be {@code https}.
 */
public final class OpaDecisionPoint implements PolicyDecisionPoint {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private final WebClient client;
    private final String path;

    /**
     * @param builder  the client builder (so a product can supply its own connector or filters in a test)
     * @param baseUrl  the OPA server, for example {@code http://localhost:8181}
     * @param rulePath the decision rule, for example {@code acme/access/decision}
     */
    public OpaDecisionPoint(WebClient.Builder builder, String baseUrl, String rulePath) {
        Objects.requireNonNull(builder, "builder");
        URI uri = URI.create(Objects.requireNonNull(baseUrl, "baseUrl"));
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && LOOPBACK.contains(uri.getHost().toLowerCase());
        if (!https && !loopback) {
            throw new IllegalArgumentException("The policy server must be https, or http on the loopback only: " + uri.getHost());
        }
        if (rulePath == null || !rulePath.matches("[A-Za-z0-9_]+(/[A-Za-z0-9_]+)*")) {
            throw new IllegalArgumentException("rulePath must look like acme/access/decision");
        }
        this.client = builder.baseUrl(baseUrl).build();
        this.path = "/v1/data/" + rulePath;
    }

    @Override
    public Mono<Decision> decide(Map<String, Object> input) {
        return client.post().uri(path).bodyValue(Map.of("input", input)).retrieve().bodyToMono(MAP)
                .flatMap(body -> {
                    Object result = body.get("result");
                    if (!(result instanceof Map<?, ?> map)) {
                        return Mono.error(new IllegalStateException("the policy gave no result"));
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    return Mono.fromSupplier(() -> Decision.parse(typed));
                });
    }
}
