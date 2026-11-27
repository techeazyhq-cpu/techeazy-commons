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

import com.techeazyhq.kit.consent.web.PurposeOfUseWebFilter;
import java.util.Set;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

/**
 * Outbound calls: HTTPS only, HTTP/2 negotiated by ALPN with HTTP/1.1 as the fallback the peer may force, and a request to an
 * {@code http://} address is refused before anything is sent. Services take {@link WebClient.Builder} by injection and get this
 * one. Calls to the hosts a service names as internal also carry who is calling ({@link #relay}).
 */
public final class OutboundHttp {

    /** The header that carries the correlation id between services. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private OutboundHttp() {
    }

    /** The Reactor Netty client: HTTP/2 over TLS, falling back to HTTP/1.1 over TLS. Never cleartext. */
    public static HttpClient httpClient() {
        return HttpClient.create().protocol(HttpProtocol.H2, HttpProtocol.HTTP11);
    }

    /** HTTPS and HTTP/2 only, and no host is internal: nothing carries the caller's identity. */
    public static WebClient.Builder webClientBuilder() {
        return webClientBuilder(httpClient(), Set.of());
    }

    /** As above, and calls to {@code internalHosts} carry the caller's identity (see {@link #relay}). */
    public static WebClient.Builder webClientBuilder(Set<String> internalHosts) {
        return webClientBuilder(httpClient(), internalHosts);
    }

    /** As above with a given Netty client, for a test or a product that tunes it. */
    public static WebClient.Builder webClientBuilder(HttpClient client, Set<String> internalHosts) {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(client)).filter(httpsOnly())
                .filter(relay(internalHosts));
    }

    /**
     * What a gateway does on the way to an internal service: sends the caller's own bearer token, correlation id and purpose of
     * use on (the service reads the tenant and the rest of the claims from the token and trusts nothing the caller wrote in a
     * header). Only to the hosts listed: a call to anyone else, an AI provider say, never carries the caller's token. A call to
     * an internal host with no request context is a bug and fails, rather than going out anonymous or as someone else. A token
     * set by the code that built the request is replaced, not trusted.
     */
    public static ExchangeFilterFunction relay(Set<String> internalHosts) {
        Set<String> hosts = Set.copyOf(internalHosts);
        return (request, next) -> {
            if (!hosts.contains(request.url().getHost())) {
                return next.exchange(request);
            }
            return Mono.deferContextual(ctx -> {
                RequestContext caller = ctx.getOrDefault(RequestContext.KEY, null);
                if (caller == null) {
                    return Mono.error(new IllegalStateException(
                            "Call to internal host " + request.url().getHost() + " without a request context"));
                }
                ClientRequest relayed = ClientRequest.from(request).headers(headers -> {
                    headers.setBearerAuth(caller.bearerToken());
                    if (caller.correlationId() != null) {
                        headers.set(CORRELATION_HEADER, caller.correlationId());
                    }
                    if (caller.purpose() != null) {
                        headers.set(PurposeOfUseWebFilter.HEADER, caller.purpose());
                    }
                }).build();
                return next.exchange(relayed);
            });
        };
    }

    /** Refuses a request whose address is not https, before anything is sent. */
    public static ExchangeFilterFunction httpsOnly() {
        return (request, next) -> {
            String scheme = request.url().getScheme();
            if (!"https".equalsIgnoreCase(scheme)) {
                return Mono.error(new IllegalArgumentException(
                        "Outbound calls must use https, refused: " + scheme + "://" + request.url().getHost()));
            }
            return next.exchange(request);
        };
    }
}
