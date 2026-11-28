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

package com.techeazyhq.kit.saga.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techeazyhq.kit.saga.ManualResolution;
import com.techeazyhq.kit.saga.SagaDefinition;
import com.techeazyhq.kit.saga.SagaEngine;
import com.techeazyhq.kit.saga.SagaInstance;
import com.techeazyhq.kit.saga.SagaListener;
import com.techeazyhq.kit.saga.SagaPhase;
import com.techeazyhq.kit.saga.SagaStatus;
import com.techeazyhq.kit.saga.Step;
import com.techeazyhq.kit.saga.StepOutcome;
import com.techeazyhq.kit.test.PgIntegrationTestBase;
import io.r2dbc.spi.ConnectionFactories;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The PostgreSQL store, against a real PostgreSQL: a saga survives a restart, and two workers cannot both move it. */
class PostgresSagaStoreTest extends PgIntegrationTestBase {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

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
    }

    private final TestClock clock = new TestClock();
    private R2dbcSagaStore store;

    @BeforeAll
    static void schema() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(SagaSchema.DDL);
        }
    }

    @BeforeEach
    void connect() throws Exception {
        store = new R2dbcSagaStore(DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/"
                + POSTGRES.getDatabaseName())));
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("TRUNCATE saga_instance");
        }
    }

    private SagaInstance newSaga(Map<String, String> data) {
        Instant now = clock.instant();
        return new SagaInstance(UUID.randomUUID(), TENANT, "facility-onboarding", "corr-1", SagaStatus.RUNNING, SagaPhase.FORWARD,
                0, 0, false, now, data, null, "platform_ops", 0, now, now);
    }

    private static SagaInstance copy(SagaInstance s, SagaStatus status, Instant next, long version) {
        return new SagaInstance(s.id(), s.tenantId(), s.type(), s.correlationId(), status, s.phase(), s.stepIndex(), s.attempt(),
                s.inFlight(), next, s.data(), s.lastError(), s.owner(), version, s.createdAt(), s.updatedAt());
    }

    @Test
    void aSagaIsStoredAndReadBackExactly() {
        SagaInstance saga = newSaga(Map.of("facility", "fac-9", "note", "quote \" backslash \\ new\nline \u00e9 \u0928"));
        store.insert(saga).block();

        assertEquals(saga, store.find(saga.id()).block());
        assertEquals(null, store.find(UUID.randomUUID()).block());
    }

    @Test
    void aChangeNeedsTheVersionItWasMadeFromAndTwoWorkersCannotBothWin() {
        SagaInstance saga = newSaga(Map.of());
        store.insert(saga).block();
        SagaInstance moved = copy(saga, saga.status(), saga.nextAttemptAt(), saga.version() + 1);

        List<Boolean> results = Flux.range(0, 6).flatMap(i -> store.save(moved, saga.version())).collectList().block();

        assertEquals(1, results.stream().filter(r -> r).count(), "exactly one worker moved it: " + results);
        assertEquals(1L, store.find(saga.id()).block().version());
        assertFalse(store.save(moved, 0).block(), "stale version");
    }

    @Test
    void theDueSagasComeOldestFirstAndOnlyWhenTheirTimeHasCome() {
        SagaInstance now = newSaga(Map.of());
        SagaInstance later = newSaga(Map.of());
        later = copy(later, later.status(), clock.instant().plusSeconds(60), 0);
        SagaInstance finished = newSaga(Map.of());
        SagaInstance done = copy(finished, SagaStatus.COMPLETED, finished.nextAttemptAt(), 0);
        for (SagaInstance s : List.of(later, now, done)) {
            store.insert(s).block();
        }

        assertEquals(List.of(now.id()), store.due(clock.instant(), 10).map(SagaInstance::id).collectList().block());
        assertEquals(2, store.due(clock.instant().plusSeconds(61), 10).count().block());
        assertEquals(List.of(now.id()), store.overdue(clock.instant().plusSeconds(5), 10).map(SagaInstance::id).collectList().block());
        assertEquals(List.of(done.id()), store.byStatus(SagaStatus.COMPLETED, 10).map(SagaInstance::id).collectList().block());
        assertEquals(Map.of("facility-onboarding/RUNNING", 2L, "facility-onboarding/COMPLETED", 1L), store.counts().block());
    }

    @Test
    void anEngineOnTheDatabaseRunsASagaToItsCompensationAndSurvivesARestartBetweenSteps() {
        List<String> calls = new CopyOnWriteArrayList<>();
        SagaDefinition definition = SagaDefinition.builder("facility-onboarding", "platform_ops")
                .step(new Step("reserve-licence", ctx -> {
                    calls.add("reserve");
                    return Mono.just(StepOutcome.done(Map.of("licence-id", "lic-1")));
                }, ctx -> {
                    calls.add("release:" + ctx.data().get("licence-id"));
                    return Mono.just(StepOutcome.done());
                }, Duration.ofSeconds(10), 3, Duration.ofSeconds(1)))
                .step(new Step("create-tenant", ctx -> {
                    calls.add("tenant");
                    return Mono.just(StepOutcome.failed("quota-exceeded"));
                }, null, Duration.ofSeconds(10), 3, Duration.ofSeconds(1)))
                .build();
        SagaEngine first = new SagaEngine(store, clock, SagaListener.NONE, List.of(definition));
        SagaInstance saga = first.start("facility-onboarding", TENANT, "corr-7", Map.of("facility", "fac-9")).block();
        first.advance(saga.id()).block(); // reserve-licence done, then the process "restarts"

        SagaEngine second = new SagaEngine(store, clock, SagaListener.NONE, List.of(definition));
        List<SagaStatus> seen = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            second.advance(saga.id()).block();
            SagaInstance now = store.find(saga.id()).block();
            seen.add(now.status());
            if (now.status().isFinished()) {
                break;
            }
        }

        SagaInstance finished = store.find(saga.id()).block();
        assertEquals(SagaStatus.COMPENSATED, finished.status());
        assertEquals(List.of("reserve", "tenant", "release:lic-1"), calls, "the data from before the restart reached the compensation");
        assertEquals("quota-exceeded", finished.lastError());
        assertEquals(Map.of("facility-onboarding/COMPENSATED", 1L), store.counts().block());
        assertTrue(second.report(Duration.ofMinutes(5)).block().manualQueue().isEmpty());
        assertFalse(ManualResolution.valueOf("RETRY") == null);
    }
}
