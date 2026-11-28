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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techeazyhq.kit.claims.Claims;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * What leaves a service: a token exchanged for the internal target, with the correlation id and purpose, to internal hosts only, and
 * https is the only scheme. The caller's own token never leaves (#613).
 */
class OutboundHttpTest {

    private static RequestContext caller(String purpose, String correlationId) {
        return new RequestContext(correlationId, "caller-token",
                new Claims("u1", UUID.randomUUID(), Set.of("staff"), Map.of(), purpose));
    }

    private static final RequestContext CALLER = caller("CLAIMS_PROCESSING", "corr-9");
    private static final Map<String, OutboundTarget> INTERNAL = Map.of("accounts.internal", OutboundTarget.of("acct-svc", "read"));

    /** Stands for the identity provider: a token for the target, naming who it is for. */
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicReference<OutboundTarget> asked = new AtomicReference<>();
    private final TokenExchange exchange = (who, target) -> {
        exchanges.incrementAndGet();
        asked.set(target);
        return Mono.just("exchanged-for-" + target.audience() + "-by-" + who.claims().subject());
    };

    private final AtomicReference<ClientRequest> sent = new AtomicReference<>();

    private WebClient client() {
        return client(exchange);
    }

    private WebClient client(TokenExchange withExchange) {
        return OutboundHttp.webClientBuilder(OutboundPolicy.defaults(), INTERNAL, withExchange)
                .exchangeFunction(request -> {
                    sent.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                }).build();
    }

    private void call(String url, RequestContext context) {
        client().get().uri(url).retrieve().toBodilessEntity().contextWrite(ctx -> ctx.put(RequestContext.KEY, context)).block();
    }

    @Test
    void aCallToAnInternalHostCarriesATokenExchangedForThatServiceNotTheCallersOwn() {
        call("https://accounts.internal/api/x", CALLER);

        assertEquals("Bearer exchanged-for-acct-svc-by-u1", sent.get().headers().getFirst("Authorization"));
        assertEquals("acct-svc", asked.get().audience());
        assertEquals(Set.of("read"), asked.get().scopes());
        assertFalse(sent.get().headers().toString().contains("caller-token"), "the caller's own token never leaves");
        assertEquals("corr-9", sent.get().headers().getFirst("X-Correlation-Id"));
        assertEquals("CLAIMS_PROCESSING", sent.get().headers().getFirst("X-Purpose-Of-Use"));
    }

    @Test
    void noClaimIsCopiedIntoAHeaderTheDownstreamServiceReadsThemFromTheToken() {
        call("https://accounts.internal/api/x", CALLER);

        assertEquals(Set.of("Authorization", "X-Correlation-Id", "X-Purpose-Of-Use"), sent.get().headers().headerNames());
    }

    @Test
    void aContextWithNoPurposeOrNoCorrelationIdRelaysNothingInventedInTheirPlace() {
        call("https://accounts.internal/api/x", caller(null, null));

        assertNull(sent.get().headers().getFirst("X-Purpose-Of-Use"));
        assertNull(sent.get().headers().getFirst("X-Correlation-Id"));
        assertEquals("Bearer exchanged-for-acct-svc-by-u1", sent.get().headers().getFirst("Authorization"));
    }

    @Test
    void aTokenSetByTheCodeCallingTheClientIsReplacedNotTrusted() {
        client().get().uri("https://accounts.internal/api/x").header("Authorization", "Bearer forged").retrieve()
                .toBodilessEntity().contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block();

        assertEquals(List.of("Bearer exchanged-for-acct-svc-by-u1"), sent.get().headers().get("Authorization"));
    }

    @Test
    void aCallToAnyOtherHostNeverCarriesAnythingOfTheCallerAndNeverCallsTheIdentityProvider() {
        call("https://llm-provider.example/v1/x", CALLER);

        assertNull(sent.get().headers().getFirst("Authorization"));
        assertNull(sent.get().headers().getFirst("X-Correlation-Id"));
        assertNull(sent.get().headers().getFirst("X-Purpose-Of-Use"));
        assertEquals(0, exchanges.get());
    }

    @Test
    void aCallToAnInternalHostWithNoRequestContextFailsInsteadOfGoingOutAnonymous() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client().get().uri("https://accounts.internal/api/x").retrieve().toBodilessEntity().block());

        assertTrue(failure.getMessage().contains("without a request context"), failure.getMessage());
        assertNull(sent.get(), "nothing was sent");
        assertEquals(0, exchanges.get());
    }

    @Test
    void whenTheTokenCannotBeExchangedTheCallIsNotMadeAndTheCallersTokenIsNeverSentInstead() {
        WebClient failing = client((who, target) -> Mono.error(new TokenExchangeException("refused", null)));

        assertThrows(TokenExchangeException.class, () -> failing.get().uri("https://accounts.internal/api/x").retrieve().toBodilessEntity()
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block());
        assertNull(sent.get(), "no call, and no fallback to the caller's own token");
    }

    @Test
    void aCleartextCallIsRefusedEvenToAnInternalHost() {
        assertThrows(IllegalArgumentException.class, () -> client().get().uri("http://accounts.internal/api/x").retrieve()
                .toBodilessEntity().contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block());
        assertNull(sent.get());
        assertEquals(0, exchanges.get());
    }

    @Test
    void aBuilderWithNoInternalHostsRelaysNothingToAnyone() {
        WebClient plain = OutboundHttp.webClientBuilder().exchangeFunction(request -> {
            sent.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).build();

        plain.get().uri("https://accounts.internal/api/x").retrieve().toBodilessEntity()
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block();

        assertNull(sent.get().headers().getFirst("Authorization"));
    }

    @Test
    void internalServicesNeedATokenExchangeThereIsNoWayToRelayTheWholeToken() {
        assertThrows(IllegalArgumentException.class, () -> OutboundHttp.webClientBuilder(OutboundPolicy.defaults(), INTERNAL, null));
        assertThrows(IllegalArgumentException.class, () -> OutboundHttp.relay(INTERNAL, null));
    }

    @Test
    void theInternalTargetsAreCopiedSoALaterChangeToTheCallersMapChangesNothing() {
        java.util.Map<String, OutboundTarget> targets = new java.util.HashMap<>(INTERNAL);
        WebClient webClient = OutboundHttp.webClientBuilder(OutboundPolicy.defaults(), targets, exchange).exchangeFunction(request -> {
            sent.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).build();
        targets.put("llm-provider.example", OutboundTarget.of("anyone"));

        webClient.get().uri("https://llm-provider.example/v1/x").retrieve().toBodilessEntity()
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block();

        assertNull(sent.get().headers().getFirst("Authorization"));
    }

    @Test
    void theHostIsMatchedWithoutRegardToCase() {
        call("https://ACCOUNTS.internal/api/x", CALLER);

        assertEquals("Bearer exchanged-for-acct-svc-by-u1", sent.get().headers().getFirst("Authorization"));
    }
}
