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

import com.techeazyhq.kit.claims.ClaimNames;
import com.techeazyhq.kit.consent.PurposeCode;
import com.techeazyhq.kit.consent.web.PurposeOfUseWebFilter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** The filter puts the verified caller in the Reactor context, and puts nothing there when the claims are not good. */
class RequestContextWebFilterTest {

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";

    private final AtomicReference<RequestContext> seen = new AtomicReference<>();
    private final AtomicReference<Boolean> hadContext = new AtomicReference<>();

    /** The rest of the chain: reads the context the way a handler does. */
    private final WebFilterChain chain = exchange -> RequestContext.current()
            .doOnNext(c -> {
                seen.set(c);
                hadContext.set(true);
            })
            .onErrorResume(IllegalStateException.class, e -> {
                hadContext.set(false);
                return Mono.empty();
            })
            .then();

    /** A bank product's names: it reads a person's branches and agent id as attributes. */
    private static final ClaimNames BANK = ClaimNames.defaults().withAttribute("branch_ids", "branch_ids")
            .withAttribute("agent_id", "agent_id");

    private static Map<String, Object> doctor() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sub", "asha");
        c.put("tenant_id", TENANT);
        c.put("branch_ids", List.of("b1", "b2"));
        c.put("roles", List.of("teller"));
        c.put("agent_id", "a-77");
        return c;
    }

    private static JwtAuthenticationToken token(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("the.signed.token").header("alg", "RS256").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).claims(c -> c.putAll(claims)).build();
        return new JwtAuthenticationToken(jwt);
    }

    private MockServerWebExchange run(RequestContextWebFilter filter, Authentication auth, PurposeCode purpose) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x")
                .header("X-Correlation-Id", "corr-1"));
        exchange.getResponse().getHeaders().set("X-Correlation-Id", "corr-1");
        if (purpose != null) {
            exchange.getAttributes().put(PurposeOfUseWebFilter.ATTRIBUTE, purpose);
        }
        Mono<Void> call = filter.filter(exchange, chain);
        if (auth != null) {
            call = call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        }
        call.block();
        return exchange;
    }

    @Test
    void aSignedInCallGetsTheClaimsOfItsVerifiedTokenTheTokenAndTheCorrelationId() {
        run(new RequestContextWebFilter(BANK), token(doctor()), null);

        assertTrue(hadContext.get());
        RequestContext ctx = seen.get();
        assertEquals(UUID.fromString(TENANT), ctx.tenantId());
        assertEquals("asha", ctx.claims().subject());
        assertEquals(Set.of("b1", "b2"), ctx.claims().attribute("branch_ids"));
        assertEquals(Set.of("teller"), ctx.claims().roles());
        assertEquals("a-77", ctx.claims().attributeValue("agent_id").orElseThrow());
        assertEquals("the.signed.token", ctx.bearerToken());
        assertEquals("corr-1", ctx.correlationId());
        assertNull(ctx.purpose());
        assertNull(ctx.purposeCode());
    }

    @Test
    void thePurposeTheRequestStatedIsAddedToTheClaims() {
        run(new RequestContextWebFilter(BANK), token(doctor()), PurposeCode.of("CARE_RECORD"));

        assertEquals("CARE_RECORD", seen.get().purpose());
        assertEquals("CARE_RECORD", seen.get().claims().purpose());
        assertEquals(PurposeCode.of("CARE_RECORD"), seen.get().purposeCode());
    }

    @Test
    void aTokenWithNoTenantGetsNoContextAndThereIsNoDefaultTenant() {
        Map<String, Object> claims = doctor();
        claims.remove("tenant_id");

        run(new RequestContextWebFilter(BANK), token(claims), PurposeCode.of("CARE_RECORD"));

        assertFalse(hadContext.get());
        assertNull(seen.get());
    }

    @Test
    void aTokenWithAnyClaimNotWellFormedGetsNoContext() {
        for (Map.Entry<String, Object> bad : Map.<String, Object>of("tenant_id", "tenant-a", "roles", List.of("tel ler"),
                "branch_ids", List.of("b1\nb2"), "agent_id", "a 77", "sub", "has space").entrySet()) {
            Map<String, Object> claims = doctor();
            claims.put(bad.getKey(), bad.getValue());
            hadContext.set(null);

            run(new RequestContextWebFilter(BANK), token(claims), null);

            assertFalse(hadContext.get(), bad.getKey());
        }
    }

    @Test
    void anAnonymousCallAndOneAuthenticatedAnotherWayGetNoContext() {
        run(new RequestContextWebFilter(BANK), null, null);
        assertFalse(hadContext.get());

        run(new RequestContextWebFilter(BANK), new UsernamePasswordAuthenticationToken("someone", "n/a", List.of()), null);
        assertFalse(hadContext.get());
    }

    @Test
    void theRequestStillGoesThroughWhenThereIsNoContext() {
        // the filter never refuses: handlers that need a caller fail loudly, others (health probes) are untouched
        MockServerWebExchange exchange = run(new RequestContextWebFilter(BANK), null, null);

        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void aProductCanNameTheClaimsItsIdentityProviderUses() {
        ClaimNames names = new ClaimNames("uid", "org", List.of("groups"), Map.of("sites", "sites"));
        Map<String, Object> claims = Map.of("uid", "u1", "org", TENANT, "sites", List.of("s1"), "groups", List.of("g1"));

        run(new RequestContextWebFilter(names), token(claims), null);

        assertEquals(Set.of("s1"), seen.get().claims().attribute("sites"));
        assertEquals(Set.of("g1"), seen.get().claims().roles());
    }

    @Test
    void theFilterRunsAfterSecurityAndThePurposeFilter() {
        assertEquals(0, new RequestContextWebFilter().getOrder());
        assertTrue(new RequestContextWebFilter().getOrder() > -50);
        assertThrows(NullPointerException.class, () -> new RequestContextWebFilter(null));
    }
}
