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

package com.techeazyhq.kit.consent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The call-site guard, with call sites a product would define: a fast read and a slower, asynchronous send. */
class ConsentGuardTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String SUBJECT = "subject-1";
    private static final PurposeCode CARE = PurposeCode.of("CARE_RECORD");
    private static final PurposeCode CARE_ACCOUNT = PurposeCode.of("ACCOUNT_VIEW");
    private static final PurposeCode MESSAGE = PurposeCode.of("SEND_MESSAGE");
    private static final PurposeCode UNRELATED = PurposeCode.of("MARKETING");

    /** The kind of sites a product defines, as an enum. */
    private enum Site implements ConsentCallSite {
        READ(Duration.ofMillis(250), Set.of(CARE, CARE_ACCOUNT)),
        SEND(Duration.ofSeconds(1), Set.of(MESSAGE));

        private final Duration timeout;
        private final Set<PurposeCode> purposes;

        Site(Duration timeout, Set<PurposeCode> purposes) {
            this.timeout = timeout;
            this.purposes = purposes;
        }

        @Override
        public Duration timeout() {
            return timeout;
        }

        @Override
        public boolean accepts(PurposeCode purpose) {
            return purposes.contains(purpose);
        }
    }

    /** A port that records every check, and answers as scripted. */
    private static final class Recording implements ConsentPort {
        final List<ConsentCheck> checks = new CopyOnWriteArrayList<>();
        final List<ConsentRequest> requests = new CopyOnWriteArrayList<>();
        final Supplier<Mono<ConsentDecision>> answer;

        Recording(Supplier<Mono<ConsentDecision>> answer) {
            this.answer = answer;
        }

        @Override
        public Mono<ConsentDecision> check(ConsentCheck check) {
            checks.add(check);
            return answer.get();
        }

        @Override
        public Mono<ConsentRequest.Result> request(ConsentRequest request) {
            requests.add(request);
            return Mono.empty();
        }

        @Override
        public Mono<Void> onConsentEvent(ConsentEvent event) {
            return Mono.empty();
        }
    }

    private static Recording allowing() {
        return new Recording(() -> Mono.just(new ConsentDecision.Allow(ConsentDecision.Basis.LIVE)));
    }

    // ---- every site calls the port --------------------------------------------------------------------------------------

    @Test
    void everySiteCallsThePortOnceWithItsTenantItsSubjectAndThePurposeAskedAbout() {
        for (Site site : Site.values()) {
            for (PurposeCode purpose : List.of(CARE, CARE_ACCOUNT, MESSAGE)) {
                if (!site.accepts(purpose)) {
                    continue;
                }
                Recording port = allowing();

                ConsentOutcome.Proceed proceed = new ConsentGuard(port).require(site, TENANT, SUBJECT, purpose).block();

                assertEquals(new ConsentOutcome.Proceed(ConsentDecision.Basis.LIVE), proceed);
                assertEquals(List.of(new ConsentCheck(TENANT, SUBJECT, purpose)), port.checks, site + " " + purpose);
            }
        }
    }

    @Test
    void aSiteIsForItsOwnPurposesOnlyAndTheWrongQuestionIsNeverAsked() {
        for (Site site : Site.values()) {
            for (PurposeCode purpose : List.of(CARE, CARE_ACCOUNT, MESSAGE, UNRELATED)) {
                Recording port = allowing();
                Mono<ConsentOutcome.Proceed> call = new ConsentGuard(port).require(site, TENANT, SUBJECT, purpose);

                if (site.accepts(purpose)) {
                    assertTrue(call.block().mayProcess());
                } else {
                    assertThrows(IllegalArgumentException.class, call::block, site + " must refuse " + purpose);
                    assertEquals(0, port.checks.size());
                }
            }
        }
    }

    @Test
    void aSiteDoesNotHaveToBeAnEnum() {
        ConsentCallSite adHoc = new ConsentCallSite() {
            @Override
            public Duration timeout() {
                return Duration.ofMillis(100);
            }

            @Override
            public boolean accepts(PurposeCode purpose) {
                return purpose.equals(CARE);
            }
        };
        Recording port = allowing();

        assertTrue(new ConsentGuard(port).require(adHoc, TENANT, SUBJECT, CARE).block().mayProcess());
        assertEquals(1, port.checks.size());
    }

    // ---- the three answers stop or let the handler continue --------------------------------------------------------------

    @Test
    void aRefusalStopsTheHandlerWithTheReasonCodeAndTheSubjectIsNotInIt() {
        Recording port = new Recording(() -> Mono.just(new ConsentDecision.Deny("no-consent")));

        ConsentRefusedException refused = assertThrows(ConsentRefusedException.class,
                () -> new ConsentGuard(port).require(Site.READ, TENANT, SUBJECT, CARE).block());

        assertEquals("no-consent", refused.reason());
        assertFalse(refused.getMessage().contains(SUBJECT));
    }

    @Test
    void unavailableAndEveryFailureStopTheHandlerAsTryLater() {
        List<Supplier<Mono<ConsentDecision>>> down = List.of(
                () -> Mono.just(new ConsentDecision.Unavailable(Duration.ofSeconds(20))),
                () -> Mono.error(new IllegalStateException("connection refused")),
                Mono::empty,
                () -> null);

        for (Supplier<Mono<ConsentDecision>> failure : down) {
            ConsentUnavailableException unavailable = assertThrows(ConsentUnavailableException.class,
                    () -> new ConsentGuard(new Recording(failure)).require(Site.READ, TENANT, SUBJECT, CARE).block());

            assertTrue(unavailable.retryAfter().compareTo(Duration.ofSeconds(1)) >= 0);
            assertFalse(unavailable.getMessage().contains(SUBJECT));
        }
    }

    @Test
    void aSiteNeverAsksThePersonForConsent() {
        for (Supplier<Mono<ConsentDecision>> answer : List.<Supplier<Mono<ConsentDecision>>>of(
                () -> Mono.just(new ConsentDecision.Deny("no-consent")),
                () -> Mono.just(new ConsentDecision.Unavailable(Duration.ofSeconds(5))),
                () -> Mono.error(new IllegalStateException("down")))) {
            Recording port = new Recording(answer);

            try {
                new ConsentGuard(port).require(Site.READ, TENANT, SUBJECT, CARE).block();
            } catch (RuntimeException expected) {
                // refused or unavailable
            }

            assertEquals(0, port.requests.size());
        }
    }

    @Test
    void aValueThatIsNotAnOpaqueReferenceNeverReachesThePort() {
        Recording port = allowing();
        ConsentGuard guard = new ConsentGuard(port);

        assertThrows(IllegalArgumentException.class, () -> guard.require(Site.READ, TENANT, "Asha Rao, diabetic", CARE).block());
        assertThrows(NullPointerException.class, () -> guard.require(Site.READ, null, SUBJECT, CARE).block());
        assertEquals(0, port.checks.size());
    }

    // ---- timeouts, per site ----------------------------------------------------------------------------------------------

    @Test
    void aReadGivesUpAfterItsOwnTimeoutAndASlowerSiteStillWaits() {
        ConsentGuard guard = new ConsentGuard(new Recording(Mono::never));

        StepVerifier.withVirtualTime(() -> guard.require(Site.READ, TENANT, SUBJECT, CARE))
                .thenAwait(Duration.ofMillis(260))
                .expectError(ConsentUnavailableException.class)
                .verify();

        StepVerifier.withVirtualTime(() -> guard.require(Site.SEND, TENANT, SUBJECT, MESSAGE))
                .thenAwait(Duration.ofMillis(900))
                .expectNoEvent(Duration.ofMillis(50))
                .thenAwait(Duration.ofMillis(100))
                .expectError(ConsentUnavailableException.class)
                .verify();
    }

    @Test
    void aSlowAnswerWithinTheSitesTimeoutIsStillUsed() {
        ConsentGuard guard = new ConsentGuard(new Recording(
                () -> Mono.delay(Duration.ofMillis(200)).thenReturn((ConsentDecision) new ConsentDecision.Allow(ConsentDecision.Basis.TOKEN))));

        StepVerifier.withVirtualTime(() -> guard.require(Site.READ, TENANT, SUBJECT, CARE))
                .thenAwait(Duration.ofMillis(200))
                .expectNext(new ConsentOutcome.Proceed(ConsentDecision.Basis.TOKEN))
                .verifyComplete();
    }

    @Test
    void theTimeoutsCanBeOverriddenForATestOrAnEnvironment() {
        ConsentGuard guard = new ConsentGuard(new Recording(Mono::never), site -> Duration.ofSeconds(5));

        StepVerifier.withVirtualTime(() -> guard.require(Site.READ, TENANT, SUBJECT, CARE))
                .thenAwait(Duration.ofMillis(4_900))
                .expectNoEvent(Duration.ofMillis(50))
                .thenAwait(Duration.ofMillis(100))
                .expectError(ConsentUnavailableException.class)
                .verify();
    }

    @Test
    void thereIsNoMemoryBetweenCalls() {
        List<ConsentDecision> answers = new ArrayList<>(List.of(new ConsentDecision.Allow(ConsentDecision.Basis.LIVE),
                new ConsentDecision.Deny("consent-withdrawn")));
        Recording port = new Recording(() -> Mono.just(answers.remove(0)));
        ConsentGuard guard = new ConsentGuard(port);

        assertTrue(guard.require(Site.READ, TENANT, SUBJECT, CARE).block().mayProcess());
        assertInstanceOf(ConsentRefusedException.class, assertThrows(RuntimeException.class,
                () -> guard.require(Site.READ, TENANT, SUBJECT, CARE).block()));
        assertEquals(2, port.checks.size());
    }

    @Test
    void theGuardRefusesNonsenseAtConstruction() {
        assertThrows(NullPointerException.class, () -> new ConsentGuard(null));
        assertThrows(NullPointerException.class, () -> new ConsentGuard(allowing(), null));
        assertThrows(NullPointerException.class, () -> new ConsentGuard(allowing()).require(null, TENANT, SUBJECT, CARE));
        assertThrows(NullPointerException.class, () -> new ConsentGuard(allowing()).require(Site.READ, TENANT, SUBJECT, null));
    }
}
