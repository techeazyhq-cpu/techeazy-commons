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

package com.techeazyhq.kit.cqrs.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techeazyhq.kit.cqrs.AggregateKey;
import com.techeazyhq.kit.cqrs.EventRecord;
import com.techeazyhq.kit.cqrs.LoggedEvent;
import com.techeazyhq.kit.cqrs.OutboxRelay;
import com.techeazyhq.kit.cqrs.Projection;
import com.techeazyhq.kit.cqrs.ProjectionRunner;
import com.techeazyhq.kit.cqrs.Reconciliation;
import com.techeazyhq.kit.cqrs.VersionConflictException;
import com.techeazyhq.kit.test.PgIntegrationTestBase;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The PostgreSQL adapters, against a real PostgreSQL: one transaction, one order, a projection rebuilt equal to its source. */
class PostgresCqrsTest extends PgIntegrationTestBase {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private DatabaseClient db;
    private TransactionalOperator tx;
    private R2dbcEventLog log;
    private R2dbcPublishCursor cursor;
    private R2dbcProjectionPositions positions;

    @BeforeAll
    static void schema() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(CqrsSchema.DDL);
            s.execute("CREATE TABLE item_name (item_id text PRIMARY KEY, name text NOT NULL)");
            s.execute("CREATE TABLE item (item_id text PRIMARY KEY, name text NOT NULL, version bigint NOT NULL)");
        }
    }

    @BeforeEach
    void connect() throws Exception {
        ConnectionFactory factory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/"
                + POSTGRES.getDatabaseName());
        db = DatabaseClient.create(factory);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        log = new R2dbcEventLog(db);
        cursor = new R2dbcPublishCursor(db);
        positions = new R2dbcProjectionPositions(db);
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("TRUNCATE cqrs_event_log, cqrs_publish_cursor, cqrs_projection_state, "
                    + "cqrs_projection_position, item_name, item RESTART IDENTITY");
        }
    }

    private static EventRecord event(String id, long version, String type, String payload) {
        return new EventRecord(UUID.randomUUID(), TENANT, "item", id, version, type, Instant.parse("2026-11-28T10:00:00Z"), payload);
    }

    /** A command: changes the aggregate's row and appends its event, in one transaction (the event is the last statement). */
    private Mono<Void> command(String id, long version, String type, String name) {
        Mono<Long> change = "ItemRemoved".equals(type)
                ? db.sql("DELETE FROM item WHERE item_id = :id").bind("id", id).fetch().rowsUpdated()
                : db.sql("INSERT INTO item (item_id, name, version) VALUES (:id, :n, :v) "
                        + "ON CONFLICT (item_id) DO UPDATE SET name = :n, version = :v")
                        .bind("id", id).bind("n", name).bind("v", version).fetch().rowsUpdated();
        return change.then(log.append(event(id, version, type, name))).as(tx::transactional);
    }

    private Map<String, String> table(String sql, String key, String value) {
        Map<String, String> out = new HashMap<>();
        db.sql(sql).map((row, meta) -> Map.entry(row.get(key, String.class), row.get(value, String.class))).all()
                .doOnNext(e -> out.put(e.getKey(), e.getValue())).blockLast();
        return out;
    }

    /** The projection: the current name of each item, in its own table. */
    private Projection namesProjection() {
        return new Projection() {
            @Override
            public String name() {
                return "item-names";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Mono<Void> apply(LoggedEvent logged) {
                EventRecord e = logged.event();
                return (e.type().equals("ItemRemoved")
                        ? db.sql("DELETE FROM item_name WHERE item_id = :id").bind("id", e.aggregateId()).fetch().rowsUpdated()
                        : db.sql("INSERT INTO item_name (item_id, name) VALUES (:id, :n) ON CONFLICT (item_id) DO UPDATE SET name = :n")
                                .bind("id", e.aggregateId()).bind("n", e.payload()).fetch().rowsUpdated()).then();
            }

            @Override
            public Mono<Void> reset() {
                return db.sql("DELETE FROM item_name").fetch().rowsUpdated().then();
            }
        };
    }

    @Test
    void theChangeAndItsEventCommitTogether() {
        command("a", 1, "ItemNamed", "Stethoscope").block();

        assertEquals(Map.of("a", "Stethoscope"), table("SELECT item_id, name FROM item", "item_id", "name"));
        assertEquals(1L, log.head().block());
        LoggedEvent logged = log.read(0, 10).blockFirst();
        assertEquals("a", logged.event().aggregateId());
        assertEquals("Stethoscope", logged.event().payload());
        assertEquals(Instant.parse("2026-11-28T10:00:00Z"), logged.event().occurredAt());
    }

    @Test
    void aFailedCommandLeavesNeitherTheChangeNorTheEvent() {
        command("a", 1, "ItemNamed", "Stethoscope").block();

        // version 5 is not the next one: the event is refused, so the change made just before it is rolled back too
        StepVerifier.create(command("a", 5, "ItemNamed", "Changed")).expectError(VersionConflictException.class).verify();

        assertEquals(Map.of("a", "Stethoscope"), table("SELECT item_id, name FROM item", "item_id", "name"));
        assertEquals(1L, log.head().block());
    }

    @Test
    void twoWritersOfTheSameVersionOneWinsAndTheOtherGetsAConflict() {
        command("a", 1, "ItemNamed", "x").block();

        List<String> outcomes = Flux.range(0, 2).flatMap(i -> command("a", 2, "ItemNamed", "writer-" + i)
                .thenReturn("won").onErrorResume(VersionConflictException.class, e -> Mono.just("conflict"))).collectList().block();

        Collections.sort(outcomes);
        assertEquals(List.of("conflict", "won"), outcomes);
        assertEquals(2L, log.head().block());
    }

    @Test
    void manyConcurrentCommandsGiveASequenceWithNoGapsThatIsTheCommitOrder() {
        int commands = 120;

        Flux.range(0, commands).flatMap(i -> command("item-" + i, 1, "ItemNamed", "n" + i), 16).blockLast();

        List<Long> sequences = new ArrayList<>();
        log.read(0, 1_000).doOnNext(e -> sequences.add(e.sequence())).blockLast();
        assertEquals(commands, sequences.size());
        for (int i = 0; i < sequences.size(); i++) {
            assertEquals(i + 1L, sequences.get(i), "contiguous and ascending");
        }
    }

    @Test
    void theRelayPublishesInOrderAndKeepsItsPlaceInTheDatabase() {
        for (long v = 1; v <= 3; v++) {
            command("a", v, "ItemNamed", "n" + v).block();
        }
        List<String> seen = new CopyOnWriteArrayList<>();
        OutboxRelay relay = new OutboxRelay("pulsar", log, cursor, e -> {
            seen.add(e.aggregateId() + "@" + e.version());
            return Mono.empty();
        }, 2);

        assertEquals(2, relay.runOnce().block());
        assertEquals(2L, cursor.get("pulsar").block());
        assertEquals(1, relay.runOnce().block());
        assertEquals(0, relay.runOnce().block());

        assertEquals(List.of("a@1", "a@2", "a@3"), seen);
        assertEquals(0L, relay.lag().block());
    }

    @Test
    void theCursorMovesOnlyOnceForTwoRelaysWithTheSameName() {
        assertTrue(cursor.advance("r", 0, 5).block());
        assertFalse(cursor.advance("r", 0, 5).block());
        assertFalse(cursor.advance("r", 3, 9).block());
        assertTrue(cursor.advance("r", 5, 9).block());
        assertEquals(9L, cursor.get("r").block());
    }

    @Test
    void aProjectionInTheDatabaseIsBuiltRebuiltAndEqualToTheSource() {
        Random random = new Random(11);
        Map<String, Long> versions = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            String id = "item-" + random.nextInt(25);
            long next = versions.merge(id, 1L, Long::sum);
            boolean exists = !table("SELECT item_id, name FROM item WHERE item_id = '" + id + "'", "item_id", "name").isEmpty();
            if (exists && random.nextInt(5) == 0) {
                command(id, next, "ItemRemoved", "").block();
            } else {
                command(id, next, "ItemNamed", "name-" + random.nextInt(100_000)).block();
            }
        }
        ProjectionRunner runner = new ProjectionRunner(namesProjection(), log, positions, tx::transactional, 50);

        runner.catchUp().block();
        Map<String, String> source = table("SELECT item_id, name FROM item", "item_id", "name");
        assertTrue(Reconciliation.compare(source, table("SELECT item_id, name FROM item_name", "item_id", "name")).isEmpty(),
                "the live projection equals the source");

        assertEquals(300L, runner.rebuild().block());

        Reconciliation.Diff diff = Reconciliation.compare(source, table("SELECT item_id, name FROM item_name", "item_id", "name"));
        assertTrue(diff.isEmpty(), "rebuilt from the events it equals the source: " + diff);
        assertEquals(0L, runner.lag().block());
        assertEquals(0L, runner.catchUp().block(), "a second run applies nothing");
    }

    @Test
    void aPositionIsUpdatedWithTheProjectionOrNotAtAll() {
        // an event whose projection fails: neither the projection row nor the position may be left behind
        command("a", 1, "ItemNamed", "x").block();
        Projection failing = new Projection() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Mono<Void> apply(LoggedEvent event) {
                return db.sql("INSERT INTO item_name (item_id, name) VALUES ('z', 'half')").fetch().rowsUpdated()
                        .then(Mono.error(new IllegalStateException("projection bug")));
            }

            @Override
            public Mono<Void> reset() {
                return Mono.empty();
            }
        };

        StepVerifier.create(new ProjectionRunner(failing, log, positions, tx::transactional, 10).catchUp())
                .expectError(IllegalStateException.class).verify();

        assertEquals(Map.of(), table("SELECT item_id, name FROM item_name", "item_id", "name"));
        assertEquals(0L, positions.lastSequence("failing").block());
        assertEquals(0L, positions.appliedVersion("failing", new AggregateKey(TENANT, "item", "a")).block());
    }
}
