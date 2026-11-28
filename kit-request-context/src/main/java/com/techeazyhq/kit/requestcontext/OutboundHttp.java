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
import java.util.Map;
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

    /** The Reactor Netty client with the default timeouts: HTTP/2 over TLS, falling back to HTTP/1.1 over TLS. Never cleartext. */
    public static HttpClient httpClient() {
        return httpClient(OutboundPolicy.defaults());
    }

    /** As above, and a connection that is not made in {@code connectTimeout}, or an answer that does not come in {@code attemptTimeout}, is cut. */
    public static HttpClient httpClient(OutboundPolicy policy) {
        return HttpClient.create().protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) policy.connectTimeout().toMillis())
                .responseTimeout(policy.attemptTimeout());
    }

    /** HTTPS and HTTP/2 only, and no host is internal: nothing carries the caller's identity. */
    public static WebClient.Builder webClientBuilder() {
        return webClientBuilder(OutboundPolicy.defaults(), Map.of(), null);
    }

    /**
     * Calls to {@code internalTargets} carry who is calling, as a token <b>exchanged</b> for the target (see {@link #relay}); every call
     * has the timeouts, bounded retries and circuit breaker of the policy (TM-15). Internal targets need a {@code TokenExchange}.
     */
    public static WebClient.Builder webClientBuilder(OutboundPolicy policy, Map<String, OutboundTarget> internalTargets, TokenExchange exchange) {
        return webClientBuilder(httpClient(policy), policy, internalTargets, exchange);
    }

    /** As above with a given Netty client, for a test or a product that tunes it. */
    public static WebClient.Builder webClientBuilder(HttpClient client, OutboundPolicy policy, Map<String, OutboundTarget> internalTargets,
                                                     TokenExchange exchange) {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(client)).filter(httpsOnly())
                .filter(relay(internalTargets, exchange)).filter(new Resilience(policy).filter());
    }

    /**
     * What a gateway does on the way to an internal service: sends a bearer token <b>exchanged</b> for that service (its audience and the
     * scopes of the call), the correlation id and the purpose of use. The caller's own token is never passed on: it is valid for every
     * service that accepts the realm's tokens, so one that received it could replay it against another (#613). The service reads the
     * tenant and the rest of the claims from the exchanged token and trusts nothing the caller wrote in a header.
     *
     * <p>Only the hosts listed receive anything: a call to anyone else, an AI provider say, carries nothing of the caller's. A call to an
     * internal host with no request context is a bug and fails, rather than going out anonymous or as someone else; so does a call whose
     * token could not be exchanged: there is no fallback to the caller's token. A token set by the code that built the request is
     * replaced, not trusted.
     */
    public static ExchangeFilterFunction relay(Map<String, OutboundTarget> internalTargets, TokenExchange exchange) {
        Map<String, OutboundTarget> targets = Map.copyOf(internalTargets);
        if (!targets.isEmpty() && exchange == null) {
            throw new IllegalArgumentException("internal services need a token exchange: the caller's own token is not passed on");
        }
        return (request, next) -> {
            OutboundTarget target = targets.get(request.url().getHost().toLowerCase(java.util.Locale.ROOT));
            if (target == null) {
                return next.exchange(request);
            }
            return Mono.deferContextual(ctx -> {
                RequestContext caller = ctx.getOrDefault(RequestContext.KEY, null);
                if (caller == null) {
                    return Mono.error(new IllegalStateException(
                            "Call to internal host " + request.url().getHost() + " without a request context"));
                }
                return exchange.exchange(caller, target).flatMap(narrowed -> {
                    ClientRequest relayed = ClientRequest.from(request).headers(headers -> {
                        headers.setBearerAuth(narrowed);
                        if (caller.correlationId() != null) {
                            headers.set(CORRELATION_HEADER, caller.correlationId());
                        }
                        if (caller.purpose() != null) {
                            headers.set(PurposeOfUseWebFilter.HEADER, caller.purpose());
                        }
                    }).build();
                    return next.exchange(relayed);
                });
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
