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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * OAuth 2.0 Token Exchange (RFC 8693) against the identity provider's token endpoint: the caller's access token goes in as the
 * {@code subject_token}, the target's audience and the scopes the call needs go in, and a new access token comes back for that audience,
 * which the provider issues for the same person and tenant. The service authenticates to the provider with its own client credentials
 * (HTTP Basic), which it holds as secrets and never logs.
 *
 * <p>Exchanged tokens are kept until shortly before they expire, per caller token, audience and scope set, so a busy service does not
 * call the provider on every request; a token is never kept longer than the provider said. Nothing the provider answers (an error body, a
 * token) is put in a message. A provider that refuses (a 4xx) is a {@link TokenExchangeException}, a platform fault to fix; a provider that is down
 * or slow is a {@code DependencyUnavailableException}, a retryable 503. Over https, or http on the loopback for a developer.
 */
public final class Rfc8693TokenExchange implements TokenExchange {

    private static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    private static final Duration SKEW = Duration.ofSeconds(10);
    private static final Duration LONGEST = Duration.ofMinutes(15);
    private static final int MAX_CACHED = 2000;
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private record Kept(String token, Instant until) {
    }

    private final WebClient client;
    private final URI endpoint;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    public Rfc8693TokenExchange(WebClient.Builder builder, String tokenEndpoint, String clientId, String clientSecret, Clock clock) {
        this.endpoint = URI.create(Objects.requireNonNull(tokenEndpoint, "tokenEndpoint"));
        boolean https = "https".equalsIgnoreCase(endpoint.getScheme());
        boolean loopback = "http".equalsIgnoreCase(endpoint.getScheme()) && endpoint.getHost() != null
                && LOOPBACK.contains(endpoint.getHost().toLowerCase());
        if (!https && !loopback) {
            throw new IllegalArgumentException("the token endpoint is https, or http on the loopback for a developer");
        }
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("the service's own client id and secret are needed to exchange tokens");
        }
        this.client = builder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<String> exchange(RequestContext caller, OutboundTarget target) {
        String key = digest(caller.bearerToken()) + "|" + target.audience() + "|" + new TreeSet<>(target.scopes());
        Kept hit = kept.get(key);
        Instant now = clock.instant();
        if (hit != null && hit.until().isAfter(now)) {
            return Mono.just(hit.token());
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT);
        form.add("subject_token", caller.bearerToken());
        form.add("subject_token_type", ACCESS_TOKEN);
        form.add("requested_token_type", ACCESS_TOKEN);
        form.add("audience", target.audience());
        if (!target.scopes().isEmpty()) {
            form.add("scope", String.join(" ", new TreeSet<>(target.scopes())));
        }
        return client.post().uri(endpoint).headers(h -> h.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData(form)).retrieve().bodyToMono(MAP)
                .map(answer -> keep(key, answer))
                .onErrorMap(e -> !(e instanceof TokenExchangeException), e -> e instanceof WebClientResponseException refused
                        && refused.getStatusCode().is4xxClientError()
                        ? new TokenExchangeException("the identity provider refused the token exchange", e)
                        : new DependencyUnavailableException("identity provider", Duration.ofSeconds(5), e));
    }

    private String keep(String key, Map<String, Object> answer) {
        Object token = answer.get("access_token");
        Object type = answer.get("token_type");
        if (!(token instanceof String access) || access.isBlank() || (type != null && !"bearer".equalsIgnoreCase(String.valueOf(type)))) {
            throw new TokenExchangeException("the token exchange answered something unusable", null);
        }
        long seconds = answer.get("expires_in") instanceof Number n ? n.longValue() : 60;
        Duration life = Duration.ofSeconds(Math.max(0, seconds)).compareTo(LONGEST) > 0 ? LONGEST : Duration.ofSeconds(Math.max(0, seconds));
        Instant until = clock.instant().plus(life).minus(SKEW);
        if (kept.size() >= MAX_CACHED) {
            Instant now = clock.instant();
            kept.values().removeIf(k -> !k.until().isAfter(now));
            if (kept.size() >= MAX_CACHED) {
                kept.clear();
            }
        }
        kept.put(key, new Kept(access, until));
        return access;
    }

    /** What is kept is keyed by a hash, so the cache never holds the caller's token itself. */
    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
