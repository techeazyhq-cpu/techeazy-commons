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

package com.techeazyhq.kit.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Deny by default, and nothing that goes wrong is an allow. */
class PolicyEnforcementPointTest {

    private static final Map<String, Object> INPUT = Map.of("action", "read");

    private static Decision allow(Map<String, Object> obligations) {
        return new Decision(true, false, List.of(), false, obligations);
    }

    private static PolicyEnforcementPoint pep(PolicyDecisionPoint pdp, ObligationHandler... handlers) {
        return new PolicyEnforcementPoint(pdp, Duration.ofMillis(200), List.of(handlers));
    }

    private static ObligationHandler counting(String key, AtomicInteger runs) {
        return new ObligationHandler() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public Mono<Void> fulfil(Decision decision, Map<String, Object> input) {
                runs.incrementAndGet();
                return Mono.empty();
            }
        };
    }

    @Test
    void anAllowProceedsAndItsObligationsAreDoneFirst() {
        AtomicInteger audits = new AtomicInteger();

        StepVerifier.create(pep(input -> Mono.just(allow(Map.of("audit", true))), counting("audit", audits)).enforce(INPUT))
                .assertNext(d -> assertEquals(1, audits.get(), "audited before the caller proceeds"))
                .verifyComplete();
    }

    @Test
    void aDenyStopsWithItsReasonsForTheLog() {
        StepVerifier.create(pep(input -> Mono.just(Decision.deny("cross-tenant"))).enforce(INPUT))
                .expectErrorSatisfies(e -> assertEquals(List.of("cross-tenant"), ((AccessDeniedException) e).reasons()))
                .verify();
    }

    @Test
    void aDecisionThatSaysNeitherAllowNorUndeterminedIsADeny() {
        StepVerifier.create(pep(input -> Mono.just(new Decision(false, false, List.of(), false, Map.of()))).enforce(INPUT))
                .expectError(AccessDeniedException.class).verify();
    }

    @Test
    void undeterminedIsTryLaterNotARefusal() {
        StepVerifier.create(pep(input -> Mono.just(new Decision(false, true, List.of(), false, Map.of()))).enforce(INPUT))
                .expectError(AccessUnavailableException.class).verify();
    }

    @Test
    void noAnswerAnErrorAndATimeoutAreNeverAnAllow() {
        StepVerifier.create(pep(input -> Mono.empty()).enforce(INPUT)).expectError(AccessUnavailableException.class).verify();
        StepVerifier.create(pep(input -> Mono.error(new IllegalStateException("boom"))).enforce(INPUT))
                .expectError(AccessUnavailableException.class).verify();
        StepVerifier.create(pep(input -> { throw new IllegalStateException("thrown"); }).enforce(INPUT))
                .expectError(AccessUnavailableException.class).verify();
        StepVerifier.create(pep(input -> Mono.never()).enforce(INPUT)).expectError(AccessUnavailableException.class).verify();
    }

    @Test
    void anAllowWithAnObligationNobodyCanMeetIsRefused() {
        AtomicInteger audits = new AtomicInteger();

        StepVerifier.create(pep(input -> Mono.just(allow(Map.of("audit", true, "notify", "security-officer"))),
                counting("audit", audits)).enforce(INPUT))
                .expectErrorSatisfies(e -> assertEquals(List.of("obligation-unsupported"), ((AccessDeniedException) e).reasons()))
                .verify();
        assertEquals(0, audits.get(), "nothing was done for an access that is refused");
    }

    @Test
    void anObligationSwitchedOffNeedsNoHandler() {
        StepVerifier.create(pep(input -> Mono.just(allow(Map.of("audit", false)))).enforce(INPUT)).expectNextCount(1).verifyComplete();
    }

    @Test
    void anObligationThatFailsStopsTheAccess() {
        ObligationHandler failing = new ObligationHandler() {
            @Override
            public String key() {
                return "audit";
            }

            @Override
            public Mono<Void> fulfil(Decision decision, Map<String, Object> input) {
                return Mono.error(new IllegalStateException("audit store down"));
            }
        };

        StepVerifier.create(pep(input -> Mono.just(allow(Map.of("audit", true))), failing).enforce(INPUT))
                .expectError(AccessUnavailableException.class).verify();
    }

    @Test
    void everyOwedObligationRuns() {
        AtomicInteger audits = new AtomicInteger();
        AtomicInteger reviews = new AtomicInteger();

        StepVerifier.create(pep(input -> Mono.just(allow(Map.of("audit", true, "break_glass", true))),
                counting("audit", audits), counting("break_glass", reviews)).enforce(INPUT)).expectNextCount(1).verifyComplete();

        assertEquals(1, audits.get());
        assertEquals(1, reviews.get());
    }

    @Test
    void aBadTimeoutIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyEnforcementPoint(input -> Mono.empty(), Duration.ZERO, List.of()));
    }
}
