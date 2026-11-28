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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techeazyhq.kit.claims.Claims;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** What leaves a service: the caller's token, correlation id and purpose go to internal hosts only, and https is the only scheme. */
class OutboundHttpTest {

    private static RequestContext caller(String purpose, String correlationId) {
        return new RequestContext(correlationId, "caller-token",
                new Claims("u1", UUID.randomUUID(), Set.of("staff"), Map.of(), purpose));
    }

    private static final RequestContext CALLER = caller("CLAIMS_PROCESSING", "corr-9");

    private final AtomicReference<ClientRequest> sent = new AtomicReference<>();

    private WebClient client() {
        return OutboundHttp.webClientBuilder(Set.of("accounts.internal"))
                .exchangeFunction(request -> {
                    sent.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                }).build();
    }

    @Test
    void aCallToAnInternalHostCarriesTheCallersTokenCorrelationIdAndPurpose() {
        client().get().uri("https://accounts.internal/api/x").retrieve().toBodilessEntity()
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block();

        assertEquals("Bearer caller-token", sent.get().headers().getFirst("Authorization"));
        assertEquals("corr-9", sent.get().headers().getFirst("X-Correlation-Id"));
        assertEquals("CLAIMS_PROCESSING", sent.get().headers().getFirst("X-Purpose-Of-Use"));
    }

    @Test
    void noClaimIsCopiedIntoAHeaderTheDownstreamServiceReadsThemFromTheToken() {
        client().get().uri("https://accounts.internal/api/x").retrieve().toBodilessEntity()
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block();

        assertEquals(Set.of("Authorization", "X-Correlation-Id", "X-Purpose-Of-Use"), sent.get().headers().headerNames());
    }

    @Test
    void aContextWithNoPurposeOrNoCorrelationIdRelaysNothingInventedInTheirPlace() {
        client().get().uri("https://accounts.internal/api/x").retrieve().toBodilessEntity()
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, caller(null, null))).block();

        assertNull(sent.get().headers().getFirst("X-Purpose-Of-Use"));
        assertNull(sent.get().headers().getFirst("X-Correlation-Id"));
        assertEquals("Bearer caller-token", sent.get().headers().getFirst("Authorization"));
    }

    @Test
    void aTokenSetByTheCodeCallingTheClientIsReplacedNotTrusted() {
        client().get().uri("https://accounts.internal/api/x").header("Authorization", "Bearer forged").retrieve()
                .toBodilessEntity().contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block();

        assertEquals(List.of("Bearer caller-token"), sent.get().headers().get("Authorization"));
    }

    @Test
    void aCallToAnyOtherHostNeverCarriesTheCallersToken() {
        client().get().uri("https://llm-provider.example/v1/x").retrieve().toBodilessEntity()
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block();

        assertNull(sent.get().headers().getFirst("Authorization"));
        assertNull(sent.get().headers().getFirst("X-Correlation-Id"));
        assertNull(sent.get().headers().getFirst("X-Purpose-Of-Use"));
    }

    @Test
    void aCallToAnInternalHostWithNoRequestContextFailsInsteadOfGoingOutAnonymous() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client().get().uri("https://accounts.internal/api/x").retrieve().toBodilessEntity().block());

        assertTrue(failure.getMessage().contains("without a request context"), failure.getMessage());
        assertNull(sent.get(), "nothing was sent");
    }

    @Test
    void aCleartextCallIsRefusedEvenToAnInternalHost() {
        assertThrows(IllegalArgumentException.class, () -> client().get().uri("http://accounts.internal/api/x").retrieve()
                .toBodilessEntity().contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block());
        assertNull(sent.get());
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
    void theInternalHostsAreCopiedSoALaterChangeToTheCallersSetChangesNothing() {
        java.util.Set<String> hosts = new java.util.HashSet<>(Set.of("accounts.internal"));
        WebClient webClient = OutboundHttp.webClientBuilder(hosts).exchangeFunction(request -> {
            sent.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).build();
        hosts.add("llm-provider.example");

        webClient.get().uri("https://llm-provider.example/v1/x").retrieve().toBodilessEntity()
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, CALLER)).block();

        assertNull(sent.get().headers().getFirst("Authorization"));
    }
}
