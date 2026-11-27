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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The fail-closed rules, proven against an adapter that does whatever the test says. The purposes below are the
 * kinds the design names: care (health data), a child's immunisation, AI context, claims. The rules are the same for all of
 * them, and the tests run each, so a future exception for one purpose has to break a test to exist.
 */
class ConsentEnforcerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final List<PurposeCode> PURPOSES = List.of(PurposeCode.of("CARE_DELIVERY"),
            PurposeCode.of("CHILD_IMMUNISATION"), PurposeCode.of("AI_CONTEXT"), PurposeCode.of("CLAIMS_PROCESSING"));

    /** An adapter that answers as scripted and counts what it is asked. */
    private static final class Scripted implements ConsentPort {
        final Supplier<Mono<ConsentDecision>> answer;
        final AtomicInteger checks = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();

        Scripted(Supplier<Mono<ConsentDecision>> answer) {
            this.answer = answer;
        }

        @Override
        public Mono<ConsentDecision> check(ConsentCheck check) {
            checks.incrementAndGet();
            return answer.get();
        }

        @Override
        public Mono<ConsentRequest.Result> request(ConsentRequest request) {
            requests.incrementAndGet();
            return Mono.empty();
        }

        @Override
        public Mono<Void> onConsentEvent(ConsentEvent event) {
            return Mono.empty();
        }
    }

    private static ConsentCheck check(PurposeCode purpose) {
        return new ConsentCheck(TENANT, "subject-1", purpose);
    }

    private static ConsentOutcome enforce(Scripted port, PurposeCode purpose) {
        return new ConsentEnforcer(port).enforce(check(purpose)).block();
    }

    // ---- the three decisions ------------------------------------------------------------------------------------------

    @Test
    void anAllowProceedsAndKeepsItsBasisForTheAudit() {
        for (PurposeCode purpose : PURPOSES) {
            ConsentOutcome outcome = enforce(new Scripted(() -> Mono.just(new ConsentDecision.Allow(ConsentDecision.Basis.LIVE))),
                    purpose);

            assertEquals(new ConsentOutcome.Proceed(ConsentDecision.Basis.LIVE), outcome);
            assertTrue(outcome.mayProcess());
        }
    }

    @Test
    void aDenyIsRefusedWithItsReasonAndMayBeAskedAgain() {
        ConsentOutcome outcome = enforce(new Scripted(() -> Mono.just(new ConsentDecision.Deny("no-consent"))), PURPOSES.get(0));

        assertEquals(new ConsentOutcome.Refused("no-consent"), outcome);
        assertFalse(outcome.mayProcess());
        assertTrue(outcome.mayAskPersonForConsent());
    }

    @Test
    void unavailableIsNeitherAllowNorDenyAndNeverPromptsThePersonAgain() {
        for (PurposeCode purpose : PURPOSES) {
            ConsentOutcome outcome = enforce(new Scripted(() -> Mono.just(new ConsentDecision.Unavailable(Duration.ofSeconds(20)))),
                    purpose);

            assertEquals(new ConsentOutcome.TryLater(Duration.ofSeconds(20)), outcome);
            assertFalse(outcome.mayProcess());
            assertFalse(outcome.mayAskPersonForConsent(), "the person has not refused");
        }
    }

    // ---- every way the adapter can fail is "try later", never "proceed" ----------------------------------------------------

    @Test
    void everyWayTheAdapterCanFailIsTryLaterForEveryPurposeAndNeverProceeds() {
        List<Supplier<Mono<ConsentDecision>>> failures = List.of(
                () -> Mono.error(new IllegalStateException("connection refused")),
                () -> Mono.error(new java.io.IOException("broken pipe")),
                () -> Mono.error(new RuntimeException()),
                () -> { throw new IllegalStateException("thrown instead of returned"); },
                () -> null,
                Mono::empty);

        for (PurposeCode purpose : PURPOSES) {
            for (Supplier<Mono<ConsentDecision>> failure : failures) {
                ConsentOutcome outcome = enforce(new Scripted(failure), purpose);

                assertEquals(new ConsentOutcome.TryLater(ConsentEnforcer.DEFAULT_RETRY), outcome, purpose.toString());
                assertFalse(outcome.mayProcess());
            }
        }
    }

    @Test
    void anAdapterThatNeverAnswersTimesOutIntoTryLater() {
        ConsentEnforcer enforcer = new ConsentEnforcer(new Scripted(Mono::never), Duration.ofSeconds(2));

        StepVerifier.withVirtualTime(() -> enforcer.enforce(check(PURPOSES.get(0))))
                .thenAwait(Duration.ofSeconds(3))
                .expectNext(new ConsentOutcome.TryLater(ConsentEnforcer.DEFAULT_RETRY))
                .verifyComplete();
    }

    @Test
    void aSlowAnswerWithinTheTimeoutIsStillUsed() {
        ConsentEnforcer enforcer = new ConsentEnforcer(new Scripted(
                () -> Mono.delay(Duration.ofSeconds(1)).thenReturn((ConsentDecision) new ConsentDecision.Allow(ConsentDecision.Basis.TOKEN))),
                Duration.ofSeconds(2));

        StepVerifier.withVirtualTime(() -> enforcer.enforce(check(PURPOSES.get(0))))
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(new ConsentOutcome.Proceed(ConsentDecision.Basis.TOKEN))
                .verifyComplete();
    }

    @Test
    void anErrorThatIsNotAnExceptionIsNotSwallowed() {
        Scripted port = new Scripted(() -> Mono.error(new OutOfMemoryError("test")));

        assertThrows(OutOfMemoryError.class, () -> new ConsentEnforcer(port).enforce(check(PURPOSES.get(0))).block());
    }

    // ---- no memory, no grace --------------------------------------------------------------------------------------------

    @Test
    void anEarlierAllowIsNeverUsedWhenTheAdapterLaterFails() {
        AtomicInteger calls = new AtomicInteger();
        Scripted port = new Scripted(() -> calls.getAndIncrement() == 0
                ? Mono.just(new ConsentDecision.Allow(ConsentDecision.Basis.LIVE))
                : Mono.error(new IllegalStateException("down")));
        ConsentEnforcer enforcer = new ConsentEnforcer(port);

        for (PurposeCode purpose : PURPOSES) {
            calls.set(0);
            assertTrue(enforcer.enforce(check(purpose)).block().mayProcess(), "first call is a live allow");
            assertFalse(enforcer.enforce(check(purpose)).block().mayProcess(), "the second must not borrow the first: " + purpose);
        }
        assertEquals(PURPOSES.size() * 2, port.checks.get(), "the enforcer asks every time and keeps nothing");
    }

    // ---- the retry interval is bounded ---------------------------------------------------------------------------------

    @Test
    void theRetryIntervalIsBoundedBothWays() {
        assertEquals(new ConsentOutcome.TryLater(Duration.ofSeconds(1)),
                enforce(new Scripted(() -> Mono.just(new ConsentDecision.Unavailable(Duration.ofMillis(1)))), PURPOSES.get(0)));
        assertEquals(new ConsentOutcome.TryLater(Duration.ofMinutes(15)),
                enforce(new Scripted(() -> Mono.just(new ConsentDecision.Unavailable(Duration.ofDays(36500)))), PURPOSES.get(0)));
    }

    // ---- the enforcer never asks the person ----------------------------------------------------------------------------

    @Test
    void theEnforcerNeverSendsAConsentRequestOnAnyPath() {
        List<Supplier<Mono<ConsentDecision>>> paths = List.of(
                () -> Mono.just(new ConsentDecision.Allow(ConsentDecision.Basis.LIVE)),
                () -> Mono.just(new ConsentDecision.Deny("no-consent")),
                () -> Mono.just(new ConsentDecision.Unavailable(Duration.ofSeconds(5))),
                () -> Mono.error(new IllegalStateException("down")),
                Mono::empty);

        for (Supplier<Mono<ConsentDecision>> path : paths) {
            Scripted port = new Scripted(path);
            enforce(port, PURPOSES.get(1));

            assertEquals(0, port.requests.get());
        }
    }

    // ---- shape ---------------------------------------------------------------------------------------------------------

    @Test
    void theOutcomeIsAlwaysOneOfThreeAndOnlyProceedMayProcess() {
        ConsentOutcome proceed = new ConsentOutcome.Proceed(ConsentDecision.Basis.STUB);
        ConsentOutcome refused = new ConsentOutcome.Refused("no-consent");
        ConsentOutcome later = new ConsentOutcome.TryLater(Duration.ofSeconds(5));

        assertTrue(proceed.mayProcess());
        assertFalse(refused.mayProcess());
        assertFalse(later.mayProcess());
        assertInstanceOf(ConsentOutcome.Proceed.class, proceed);
    }

    @Test
    void theEnforcerRefusesNonsenseAtConstruction() {
        Scripted port = new Scripted(Mono::empty);

        assertThrows(NullPointerException.class, () -> new ConsentEnforcer(null));
        assertThrows(IllegalArgumentException.class, () -> new ConsentEnforcer(port, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ConsentEnforcer(port, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ConsentEnforcer(port, null));
        assertThrows(NullPointerException.class, () -> new ConsentEnforcer(port).enforce(null));
    }
}
