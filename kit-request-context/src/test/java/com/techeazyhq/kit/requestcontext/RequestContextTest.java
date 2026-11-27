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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techeazyhq.kit.claims.Claims;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class RequestContextTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static RequestContext of(UUID tenant, String purpose) {
        Claims claims = new Claims("u1", tenant, Set.of("f1"), Set.of("physician"), null, null, purpose);
        return new RequestContext("corr-1", "secret.token.value", claims);
    }

    @Test
    void theTenantAndPurposeComeFromTheClaims() {
        RequestContext ctx = of(TENANT, "CARE_RECORD");

        assertEquals(TENANT, ctx.tenantId());
        assertEquals("CARE_RECORD", ctx.purpose());
        assertEquals("CARE_RECORD", ctx.purposeCode().value());
    }

    @Test
    void thereIsNoContextOutsideARequestAndItFailsLoudlyInsteadOfDefaultingTheTenant() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> RequestContext.current().block());

        assertTrue(failure.getMessage().contains("No request context"));
    }

    @Test
    void itFollowsTheRequestAcrossOperatorsAndSchedulers() {
        RequestContext ctx = of(TENANT, "CARE_RECORD");

        RequestContext seen = RequestContext.current()
                .publishOn(Schedulers.boundedElastic()).flatMap(c -> Mono.delay(Duration.ofMillis(5)).thenReturn(c))
                .publishOn(Schedulers.parallel()).flatMap(c -> RequestContext.current())
                .contextWrite(c -> c.put(RequestContext.KEY, ctx)).block();

        assertEquals(ctx, seen);
    }

    @Test
    void manyRequestsAtOnceNeverSeeEachOthersContext() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        List<String> mismatches = Flux.range(0, 400).flatMap(i -> {
            UUID mine = i % 2 == 0 ? a : b;
            return RequestContext.current().publishOn(Schedulers.parallel()).delayElement(Duration.ofMillis(1))
                    .filter(c -> !c.tenantId().equals(mine)).map(c -> "sent " + mine + " got " + c.tenantId())
                    .contextWrite(c -> c.put(RequestContext.KEY, of(mine, null)));
        }, 32).collectList().block();

        assertEquals(List.of(), mismatches);
    }

    @Test
    void toStringNeverPrintsTheToken() {
        String text = of(TENANT, "CARE_RECORD").toString();

        assertFalse(text.contains("secret.token.value"));
        assertTrue(text.contains("corr-1") && text.contains(TENANT.toString()));
    }

    @Test
    void aContextNeedsItsTokenAndItsClaims() {
        Claims claims = new Claims("u1", TENANT, Set.of(), Set.of(), null, null, null);

        assertThrows(NullPointerException.class, () -> new RequestContext("c", null, claims));
        assertThrows(NullPointerException.class, () -> new RequestContext("c", "t", null));
        assertEquals(null, new RequestContext(null, "t", claims).correlationId());
    }
}
