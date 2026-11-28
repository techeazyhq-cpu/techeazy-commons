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

package com.techeazyhq.kit.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The failure and compensation scenarios (EP-57-09 acceptance). A saga that books a facility: reserve a licence, create the
 * tenant, create the identity client. Time is a clock the test moves.
 */
class SagaEngineTest {

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-28T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private static final UUID TENANT = UUID.randomUUID();

    private final TestClock clock = new TestClock();
    private final InMemorySagaStore store = new InMemorySagaStore();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<SagaInstance> changes = new CopyOnWriteArrayList<>();

    private SagaEngine engine(SagaDefinition definition) {
        return new SagaEngine(store, clock, changes::add, List.of(definition));
    }

    /** A step that records what it did, with the key it was given, and does whatever the test says. */
    private Step step(String name, StepBehaviour action, StepBehaviour compensation, int attempts) {
        return new Step(name, ctx -> {
            calls.add(name + ":do#" + ctx.attempt());
            keys.add(ctx.idempotencyKey());
            return action.run(ctx);
        }, compensation == null ? null : ctx -> {
            calls.add(name + ":undo#" + ctx.attempt());
            keys.add(ctx.idempotencyKey());
            return compensation.run(ctx);
        }, Duration.ofSeconds(10), attempts, Duration.ofSeconds(2));
    }

    @FunctionalInterface
    interface StepBehaviour {
        Mono<StepOutcome> run(StepContext ctx);
    }

    private static final StepBehaviour OK = ctx -> Mono.just(StepOutcome.done(Map.of(ctx.step() + "-id", "id-of-" + ctx.step())));

    private SagaDefinition saga(StepBehaviour second, StepBehaviour secondUndo, StepBehaviour thirdBehaviour, StepBehaviour thirdUndo,
                                int attempts) {
        return SagaDefinition.builder("facility-onboarding", "platform_ops")
                .step(step("reserve-licence", OK, OK, attempts))
                .step(step("create-tenant", second, secondUndo, attempts))
                .step(step("create-client", thirdBehaviour, thirdUndo, attempts))
                .build();
    }

    /** Runs the engine, moving the clock past any wait, until nothing is left to do or the loop limit. */
    private SagaInstance drive(SagaEngine engine, UUID id) {
        for (int i = 0; i < 100; i++) {
            engine.advance(id).block();
            SagaInstance s = store.find(id).block();
            if (s.status().isFinished()) {
                return s;
            }
            clock.advance(Duration.ofSeconds(11));
        }
        return store.find(id).block();
    }

    private SagaInstance start(SagaEngine engine) {
        return engine.start("facility-onboarding", TENANT, "corr-1", Map.of("facility", "fac-9")).block();
    }

    // ---- the happy path ------------------------------------------------------------------------------------------------------

    @Test
    void everyStepRunsInOrderAndTheDataTheyProduceIsPassedOn() {
        SagaEngine engine = engine(saga(OK, OK, OK, OK, 3));
        SagaInstance saga = start(engine);

        SagaInstance done = drive(engine, saga.id());

        assertEquals(SagaStatus.COMPLETED, done.status());
        assertEquals(List.of("reserve-licence:do#1", "create-tenant:do#1", "create-client:do#1"), calls);
        assertEquals("id-of-create-tenant", done.data().get("create-tenant-id"));
        assertEquals("fac-9", done.data().get("facility"));
    }

    // ---- failure and compensation ----------------------------------------------------------------------------------------------

    @Test
    void aStepThatCannotSucceedUndoesTheStepsBeforeItInReverseOrder() {
        SagaEngine engine = engine(saga(OK, OK, ctx -> Mono.just(StepOutcome.failed("quota-exceeded")), OK, 3));
        SagaInstance saga = start(engine);

        SagaInstance done = drive(engine, saga.id());

        assertEquals(SagaStatus.COMPENSATED, done.status());
        assertEquals(List.of("reserve-licence:do#1", "create-tenant:do#1", "create-client:do#1",
                "create-tenant:undo#1", "reserve-licence:undo#1"), calls, "the failed step is not undone: it did nothing");
        assertEquals("quota-exceeded", done.lastError());
    }

    @Test
    void aFirstStepThatFailsLeavesNothingToUndo() {
        SagaDefinition definition = SagaDefinition.builder("facility-onboarding", "platform_ops")
                .step(step("reserve-licence", ctx -> Mono.just(StepOutcome.failed("licence-invalid")), OK, 3))
                .step(step("create-tenant", OK, OK, 3)).build();
        SagaEngine engine = engine(definition);
        SagaInstance saga = start(engine);

        assertEquals(SagaStatus.COMPENSATED, drive(engine, saga.id()).status());
        assertEquals(List.of("reserve-licence:do#1"), calls);
    }

    @Test
    void aStepThatFailsThenSucceedsIsRetriedAfterItsBackoffWithTheSameKey() {
        AtomicInteger tries = new AtomicInteger();
        StepBehaviour flaky = ctx -> tries.incrementAndGet() < 3 ? Mono.error(new IllegalStateException("down")) : OK.run(ctx);
        SagaEngine engine = engine(saga(flaky, OK, OK, OK, 5));
        SagaInstance saga = start(engine);

        SagaInstance done = drive(engine, saga.id());

        assertEquals(SagaStatus.COMPLETED, done.status());
        assertEquals(3, calls.stream().filter(c -> c.startsWith("create-tenant:do")).count());
        Set<String> tenantKeys = new HashSet<>();
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith("create-tenant:do")) {
                tenantKeys.add(keys.get(i));
            }
        }
        assertEquals(1, tenantKeys.size(), "every attempt of a step carries the same idempotency key");
    }

    @Test
    void aRetryWaitsForItsBackoffBeforeRunningAgain() {
        StepBehaviour down = ctx -> Mono.error(new IllegalStateException("down"));
        SagaEngine engine = engine(saga(down, OK, OK, OK, 3));
        SagaInstance saga = start(engine);
        engine.advance(saga.id()).block(); // reserve-licence
        engine.advance(saga.id()).block(); // create-tenant fails, retry in 2 s

        engine.advance(saga.id()).block(); // not due yet
        assertEquals(2, calls.size());

        clock.advance(Duration.ofSeconds(2));
        engine.advance(saga.id()).block();
        assertEquals(3, calls.size());
    }

    @Test
    void whenAllAttemptsFailTheStepIsCompensatedToo_BecauseItMayHaveTakenEffectUnseen() {
        SagaEngine engine = engine(saga(ctx -> Mono.error(new IllegalStateException("answer lost")), OK, OK, OK, 2));
        SagaInstance saga = start(engine);

        SagaInstance done = drive(engine, saga.id());

        assertEquals(SagaStatus.COMPENSATED, done.status());
        assertEquals(List.of("reserve-licence:do#1", "create-tenant:do#1", "create-tenant:do#2", "create-tenant:undo#1",
                "reserve-licence:undo#1"), calls);
    }

    @Test
    void theCompensationHasItsOwnKeyDifferentFromTheAction() {
        SagaEngine engine = engine(saga(OK, OK, ctx -> Mono.just(StepOutcome.failed("no")), OK, 3));
        drive(engine, start(engine).id());

        assertNotEquals(keys.get(1), keys.get(3), "create-tenant do and undo");
        assertTrue(keys.get(1).endsWith(":create-tenant:do") && keys.get(3).endsWith(":create-tenant:undo"));
    }

    @Test
    void aCompensationThatCannotBeCompletedGoesToTheManualQueueOwnedByTheTeam() {
        StepBehaviour stuck = ctx -> Mono.error(new IllegalStateException("identity provider down"));
        SagaEngine engine = engine(saga(OK, stuck, ctx -> Mono.just(StepOutcome.failed("quota-exceeded")), OK, 2));
        SagaInstance saga = start(engine);

        SagaInstance done = drive(engine, saga.id());

        assertEquals(SagaStatus.NEEDS_INTERVENTION, done.status());
        assertEquals("platform_ops", done.owner());
        assertEquals(SagaPhase.COMPENSATING, done.phase());
        assertEquals("step-error", done.lastError());
        assertEquals(1, engine.report(Duration.ofMinutes(5)).block().manualQueue().size());
        assertTrue(engine.report(Duration.ofMinutes(5)).block().needsAttention());
    }

    @Test
    void theTeamCanRetryTheCompensationOnceTheCauseIsFixedAndTheSagaFinishes() {
        AtomicInteger undoTries = new AtomicInteger();
        StepBehaviour undo = ctx -> undoTries.incrementAndGet() <= 2 ? Mono.error(new IllegalStateException("down")) : OK.run(ctx);
        SagaEngine engine = engine(saga(OK, undo, ctx -> Mono.just(StepOutcome.failed("quota-exceeded")), OK, 2));
        SagaInstance saga = start(engine);
        assertEquals(SagaStatus.NEEDS_INTERVENTION, drive(engine, saga.id()).status());

        assertTrue(engine.resolve(saga.id(), ManualResolution.RETRY, "ops-lead-1").block());
        SagaInstance done = drive(engine, saga.id());

        assertEquals(SagaStatus.COMPENSATED, done.status());
        assertEquals(List.of("reserve-licence:do#1", "create-tenant:do#1", "create-client:do#1", "create-tenant:undo#1",
                "create-tenant:undo#2", "create-tenant:undo#1", "reserve-licence:undo#1"), calls);
        assertFalse(engine.report(Duration.ofMinutes(5)).block().needsAttention());
    }

    @Test
    void theTeamCanCloseASagaItUndidByHandAndOnlyOneInTheQueue() {
        SagaEngine engine = engine(saga(OK, ctx -> Mono.error(new IllegalStateException("x")),
                ctx -> Mono.just(StepOutcome.failed("no")), OK, 1));
        SagaInstance saga = start(engine);
        drive(engine, saga.id());

        assertTrue(engine.resolve(saga.id(), ManualResolution.MARK_COMPENSATED, "ops-lead-1").block());
        assertEquals(SagaStatus.COMPENSATED, store.find(saga.id()).block().status());
        assertFalse(engine.resolve(saga.id(), ManualResolution.RETRY, "ops-lead-1").block(), "no longer in the queue");
        assertThrows(IllegalArgumentException.class, () -> engine.resolve(saga.id(), ManualResolution.RETRY, " ").block());
    }

    @Test
    void aStepWithNoCompensationIsSkippedWhenUndoing() {
        SagaDefinition definition = SagaDefinition.builder("facility-onboarding", "platform_ops")
                .step(step("reserve-licence", OK, OK, 3))
                .step(step("send-welcome", OK, null, 3))
                .step(step("create-tenant", ctx -> Mono.just(StepOutcome.failed("no")), OK, 3)).build();
        SagaEngine engine = engine(definition);

        assertEquals(SagaStatus.COMPENSATED, drive(engine, start(engine).id()).status());
        assertEquals(List.of("reserve-licence:do#1", "send-welcome:do#1", "create-tenant:do#1", "reserve-licence:undo#1"), calls);
    }

    // ---- timeouts, waiting and crashes -----------------------------------------------------------------------------------------

    @Test
    void aStepThatNeverAnswersTimesOutAndIsRetried() {
        AtomicInteger tries = new AtomicInteger();
        StepBehaviour slow = ctx -> tries.incrementAndGet() == 1 ? Mono.never() : OK.run(ctx);
        SagaEngine engine = engine(saga(slow, OK, OK, OK, 3));

        SagaInstance done = drive(engine, start(engine).id());

        assertEquals(SagaStatus.COMPLETED, done.status());
        assertEquals(2, calls.stream().filter(c -> c.startsWith("create-tenant:do")).count());
    }

    @Test
    void aStepThatWaitsForAnEventContinuesWhenTheEventArrives() {
        SagaEngine engine = engine(saga(ctx -> Mono.just(StepOutcome.waiting()), OK, OK, OK, 3));
        SagaInstance saga = start(engine);
        engine.advance(saga.id()).block();
        engine.advance(saga.id()).block(); // create-tenant sends its command and waits

        assertTrue(store.find(saga.id()).block().inFlight());
        assertTrue(engine.signal(saga.id(), "create-tenant", SagaPhase.FORWARD, StepOutcome.done(Map.of("tenant-id", "t-1"))).block());

        SagaInstance done = drive(engine, saga.id());
        assertEquals(SagaStatus.COMPLETED, done.status());
        assertEquals("t-1", done.data().get("tenant-id"));
    }

    @Test
    void aDuplicateOrLateOrMisaddressedSignalChangesNothing() {
        SagaEngine engine = engine(saga(ctx -> Mono.just(StepOutcome.waiting()), OK, OK, OK, 3));
        SagaInstance saga = start(engine);
        engine.advance(saga.id()).block();
        engine.advance(saga.id()).block();

        assertFalse(engine.signal(saga.id(), "create-client", SagaPhase.FORWARD, StepOutcome.done()).block(), "wrong step");
        assertFalse(engine.signal(saga.id(), "create-tenant", SagaPhase.COMPENSATING, StepOutcome.done()).block(), "wrong direction");
        assertFalse(engine.signal(UUID.randomUUID(), "create-tenant", SagaPhase.FORWARD, StepOutcome.done()).block(), "no such saga");
        assertTrue(engine.signal(saga.id(), "create-tenant", SagaPhase.FORWARD, StepOutcome.done()).block());
        assertFalse(engine.signal(saga.id(), "create-tenant", SagaPhase.FORWARD, StepOutcome.done()).block(), "duplicate");
    }

    @Test
    void aFailedSignalStartsCompensation() {
        SagaEngine engine = engine(saga(ctx -> Mono.just(StepOutcome.waiting()), OK, OK, OK, 3));
        SagaInstance saga = start(engine);
        engine.advance(saga.id()).block();
        engine.advance(saga.id()).block();

        engine.signal(saga.id(), "create-tenant", SagaPhase.FORWARD, StepOutcome.failed("tenant-rejected")).block();

        assertEquals(SagaStatus.COMPENSATED, drive(engine, saga.id()).status());
        assertEquals("tenant-rejected", store.find(saga.id()).block().lastError());
    }

    @Test
    void aWorkerThatDiesMidStepIsReplacedAndTheStepRunsAgainWithTheSameKey() {
        SagaEngine engine = engine(saga(OK, OK, OK, OK, 3));
        SagaInstance saga = start(engine);
        // the first worker claimed create-tenant, then the process died: the saga is left in flight with a deadline
        engine.advance(saga.id()).block();
        SagaInstance atTenant = store.find(saga.id()).block();
        SagaInstance claimed = atTenant.change(clock.instant(), d -> {
            d.attempt = 1;
            d.inFlight = true;
            d.nextAttemptAt = clock.instant().plusSeconds(10);
        });
        assertTrue(store.save(claimed, atTenant.version()).block());

        engine.advance(saga.id()).block();
        assertEquals(0, calls.stream().filter(c -> c.startsWith("create-tenant")).count(), "its deadline has not passed");

        clock.advance(Duration.ofSeconds(11));
        SagaInstance done = drive(engine, saga.id());

        assertEquals(SagaStatus.COMPLETED, done.status());
        assertEquals(1, calls.stream().filter(c -> c.startsWith("create-tenant:do")).count());
    }

    @Test
    void twoWorkersOnTheSameSagaRunTheStepOnce() {
        SagaEngine engine = engine(saga(OK, OK, OK, OK, 3));
        SagaInstance saga = start(engine);

        Flux.range(0, 8).flatMap(i -> engine.advance(saga.id()).onErrorResume(e -> Mono.empty())).blockLast();

        assertEquals(1, calls.stream().filter(c -> c.startsWith("reserve-licence")).count(), "claimed by exactly one worker");
    }

    // ---- seeing ------------------------------------------------------------------------------------------------------------

    @Test
    void theReportCountsSagasByTypeAndStatusAndFlagsTheOnesNobodyMoves() {
        SagaEngine engine = engine(saga(OK, OK, OK, OK, 3));
        SagaInstance finished = start(engine);
        drive(engine, finished.id());
        SagaInstance waiting = start(engine);

        assertEquals(Map.of("facility-onboarding/COMPLETED", 1L, "facility-onboarding/RUNNING", 1L),
                engine.report(Duration.ofMinutes(5)).block().counts());
        assertTrue(engine.report(Duration.ofMinutes(5)).block().stuck().isEmpty());

        clock.advance(Duration.ofMinutes(6));
        SagaReport report = engine.report(Duration.ofMinutes(5)).block();

        assertEquals(List.of(waiting.id()), report.stuck().stream().map(SagaInstance::id).toList());
        assertTrue(report.needsAttention());
    }

    @Test
    void runDueMovesEverySagaThatIsDueAndOneFailingNeverStopsTheRest() {
        SagaEngine engine = engine(saga(OK, OK, OK, OK, 3));
        for (int i = 0; i < 5; i++) {
            start(engine);
        }

        assertEquals(5L, engine.runDue(10).block());
        assertEquals(5, calls.stream().filter(c -> c.startsWith("reserve-licence")).count());
    }

    @Test
    void everyChangeIsToldToTheListenerAndAThrowingListenerChangesNothing() {
        SagaEngine throwing = new SagaEngine(store, clock, s -> {
            throw new IllegalStateException("metrics down");
        }, List.of(saga(OK, OK, OK, OK, 3)));

        assertEquals(SagaStatus.COMPLETED, drive(throwing, start(throwing).id()).status());

        SagaEngine counted = engine(saga(OK, OK, OK, OK, 3));
        drive(counted, start(counted).id());
        assertTrue(changes.size() >= 4, "start and every step");
    }

    // ---- what is allowed ------------------------------------------------------------------------------------------------------

    @Test
    void aSagaNeedsTwoStepsAnOwnerAndUniqueNames() {
        assertThrows(IllegalArgumentException.class, () -> SagaDefinition.builder("one", "ops").step("only", OK::run).build());
        assertThrows(IllegalArgumentException.class, () -> SagaDefinition.builder("one", "Not An Owner"));
        assertThrows(IllegalArgumentException.class, () -> SagaDefinition.builder("one", "ops").step("a", OK::run).step("a", OK::run).build());
        assertThrows(IllegalArgumentException.class, () -> new Step("Bad Name", OK::run, null, Duration.ofSeconds(1), 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Step("ok", OK::run, null, Duration.ZERO, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Step("ok", OK::run, null, Duration.ofSeconds(1), 0, Duration.ZERO));
    }

    @Test
    void sagaDataIsIdsAndShortValuesNeverRecords() {
        SagaEngine engine = engine(saga(OK, OK, OK, OK, 3));

        assertThrows(IllegalArgumentException.class, () -> engine.start("facility-onboarding", TENANT, "corr", Map.of("note", "x".repeat(300))).block());
        assertThrows(IllegalArgumentException.class, () -> engine.start("facility-onboarding", TENANT, "corr", Map.of("bad key", "v")).block());
        assertThrows(IllegalArgumentException.class, () -> engine.start("no-such-saga", TENANT, "corr", Map.of()).block());
        assertFalse(start(engine).toString().contains("fac-9"), "the data is never printed");
    }

    @Test
    void manyConcurrentSagasAllFinishWithEveryStepRunOnce() {
        SagaEngine engine = engine(saga(OK, OK, OK, OK, 3));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(start(engine).id());
        }

        for (int round = 0; round < 5; round++) {
            engine.runDue(100).block();
        }

        assertTrue(ids.stream().allMatch(id -> store.find(id).block().status() == SagaStatus.COMPLETED));
        assertEquals(40, calls.stream().filter(c -> c.startsWith("create-client")).count());
    }
}
