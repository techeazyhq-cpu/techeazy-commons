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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techeazyhq.kit.cqrs.AppendContendedException;
import com.techeazyhq.kit.cqrs.EventRecord;
import com.techeazyhq.kit.cqrs.LoggedEvent;
import com.techeazyhq.kit.cqrs.VersionConflictException;
import com.techeazyhq.kit.test.PgIntegrationTestBase;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * #616 (RM-D1): what the single append lock costs one tenant when others write. Appends take one lock so the log's sequence is the
 * commit order; the lock is held from the append until the transaction commits. Measured against a real PostgreSQL, so "does it
 * matter" has a number, and the guarantee (no gap, commit order) is checked under the same load.
 *
 * <p>Two cases. <b>Many tenants</b>, commands that follow the contract (the append is the last statement): the latency each sees as
 * the number of tenants grows. <b>A slow neighbour</b> that breaks the contract and holds the lock 150 ms after each append, the
 * worst case the finding names (a slow commit): the latency a quiet tenant sees, without a bound on the wait and with one. The
 * figures are printed; the assertions are the write budget of the platform (p99 2 s) and the guarantee.
 */
class EventLogContentionTest extends PgIntegrationTestBase {

    private static final Duration SLOW = Duration.ofMillis(150);

    private DatabaseClient db;
    private TransactionalOperator tx;
    private R2dbcEventLog log;
    private ConnectionPool pool;

    @org.junit.jupiter.api.AfterEach
    void closePool() {
        if (pool != null) {
            pool.dispose();
            pool = null;
        }
    }

    @BeforeAll
    static void schema() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(CqrsSchema.DDL);
        }
    }

    private void connect(Duration lockWait) {
        connect(lockWait, null);
    }

    private void connect(Duration lockWait, Duration gapTolerance) {
        // each test starts from an empty log: a sequence gap left by an earlier test would hold a reader for its tolerance
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute("TRUNCATE cqrs_event_log RESTART IDENTITY");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        ConnectionFactory factory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/"
                + POSTGRES.getDatabaseName());
        closePool();
        pool = new ConnectionPool(ConnectionPoolConfiguration.builder(factory).maxSize(40).build());
        db = DatabaseClient.create(pool);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
        log = gapTolerance != null ? R2dbcEventLog.concurrent(db, gapTolerance)
                : lockWait == null ? new R2dbcEventLog(db) : new R2dbcEventLog(db, lockWait);
    }

    private static EventRecord event(UUID tenant, String aggregate) {
        return new EventRecord(UUID.randomUUID(), tenant, "item", aggregate, 1, "ItemNamed", Instant.now(), "{}");
    }

    /** One command. With {@code holdAfterAppend} it breaks the contract and keeps its transaction open after the append. */
    private Mono<Long> command(UUID tenant, String aggregate, Duration holdAfterAppend) {
        long start = System.nanoTime();
        Mono<Void> work = log.append(event(tenant, aggregate));
        if (!holdAfterAppend.isZero()) {
            work = work.then(db.sql("SELECT pg_sleep(:s)").bind("s", holdAfterAppend.toMillis() / 1000.0).fetch().rowsUpdated().then());
        }
        return work.as(tx::transactional).then(Mono.fromSupplier(() -> (System.nanoTime() - start) / 1_000_000));
    }

    private static long percentile(List<Long> millis, double p) {
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        return sorted.get(Math.max(0, (int) Math.ceil(p * sorted.size()) - 1));
    }

    private List<Long> tenantsWriting(int tenants, int commandsEach) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < tenants; i++) {
            ids.add(UUID.randomUUID());
        }
        return Flux.fromIterable(ids)
                .flatMap(t -> Flux.range(1, commandsEach).concatMap(n -> command(t, "a-" + n, Duration.ZERO)), tenants)
                .collectList().block(Duration.ofMinutes(2));
    }

    @Test
    void theCostOfTheSharedLockAsTenantsWriteTogetherAndWhatNoLockGives() {
        StringBuilder table = new StringBuilder("\n#616 append latency (append + commit), commands that follow the contract, ms\n"
                + " mode        | tenants | commands |  p50 |  p95 |  p99 | throughput/s\n");
        for (String mode : new String[] {"serialised", "concurrent"}) {
            connect(null, mode.equals("concurrent") ? Duration.ofSeconds(5) : null);
            tenantsWriting(4, 10); // the pool and the first statements warm up, so the table is the steady state
            for (int tenants : new int[] {1, 8, 32}) {
                long started = System.nanoTime();
                List<Long> latencies = tenantsWriting(tenants, 40);
                double seconds = (System.nanoTime() - started) / 1e9;
                table.append(String.format(" %-11s | %7d | %8d | %4d | %4d | %4d | %12.0f%n", mode, tenants, latencies.size(),
                        percentile(latencies, 0.5), percentile(latencies, 0.95), percentile(latencies, 0.99), latencies.size() / seconds));
                assertTrue(percentile(latencies, 0.99) < 2_000, "p99 within the write budget with " + tenants + " tenants, " + mode);
            }
        }
        System.out.println(table);
        assertNoGapAndCommitOrder();
    }

    private List<Long> quietBesideSlowNeighbour(UUID noisy, UUID quiet, AtomicInteger refused) {
        Flux<Long> neighbour = Flux.range(1, 30).concatMap(n -> command(noisy, "n-" + n, SLOW).onErrorResume(e -> Mono.empty()));
        Flux<Long> quietOne = Flux.range(1, 30).delayElements(Duration.ofMillis(40)).concatMap(n -> command(quiet, "q-" + n, Duration.ZERO)
                .onErrorResume(AppendContendedException.class, e -> {
                    refused.incrementAndGet();
                    return Mono.empty();
                }));
        return Flux.merge(neighbour.then(Mono.<Long>empty()), quietOne).collectList().block(Duration.ofMinutes(2));
    }

    @Test
    void aQuietTenantBesideASlowNeighbourWaitsForItWithoutABoundAndIsRefusedAtOnceWithOne() {
        UUID noisy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();

        connect(null);
        List<Long> unbounded = quietBesideSlowNeighbour(noisy, quiet, new AtomicInteger());
        connect(Duration.ofMillis(50));
        AtomicInteger refused = new AtomicInteger();
        List<Long> bounded = quietBesideSlowNeighbour(UUID.randomUUID(), UUID.randomUUID(), refused);

        System.out.printf("%n#616 a quiet tenant beside a neighbour that holds the lock %d ms after each append, ms%n"
                        + " unbounded wait: %d commands, p50 %d, p99 %d%n"
                        + " bounded wait (50 ms): %d of 30 refused with a retryable error; the %d that ran: p50 %d, p99 %d%n",
                SLOW.toMillis(), unbounded.size(), percentile(unbounded, 0.5), percentile(unbounded, 0.99), refused.get(), bounded.size(),
                percentile(bounded, 0.5), percentile(bounded, 0.99));
        assertTrue(percentile(unbounded, 0.99) < 2_000, "at this load even the worst case stays inside the write budget");
        assertTrue(refused.get() > 0, "a wait over the bound is refused, not queued behind the neighbour");
        assertTrue(percentile(bounded, 0.99) < 2 * SLOW.toMillis() + 100, "and what runs is not held behind more than one neighbour");
        assertEquals(30, refused.get() + bounded.size(), "every command either ran or was refused: none lost");
        assertNoGapAndCommitOrder();
    }

    @Test
    void withNoLockAQuietTenantDoesNotFeelASlowNeighbour() {
        connect(null, Duration.ofSeconds(5));
        tenantsWriting(2, 5);

        List<Long> quiet = quietBesideSlowNeighbour(UUID.randomUUID(), UUID.randomUUID(), new AtomicInteger());

        System.out.printf("%n#616 the same slow neighbour with no lock: the quiet tenant p50 %d, p99 %d ms (with the lock: p50 ~160)%n",
                percentile(quiet, 0.5), percentile(quiet, 0.99));
        assertEquals(30, quiet.size());
        assertTrue(percentile(quiet, 0.99) < SLOW.toMillis(), "the neighbour's hold is not added to the quiet tenant's append");
    }

    /** The guarantee that matters to a reader: it never moves past an event that has not committed yet and then misses it. */
    @Test
    void aLateCommitBehindAHigherSequenceIsNotSkippedByAReader() {
        connect(null, Duration.ofSeconds(5));
        UUID slow = UUID.randomUUID();
        UUID fast = UUID.randomUUID();
        long before = log.head().block();

        // takes sequence N and commits late; takes N+1 afterwards and commits at once
        Mono<Long> late = command(slow, "late", Duration.ofMillis(600));
        Mono<Long> early = Mono.delay(Duration.ofMillis(150)).then(command(fast, "early", Duration.ZERO));
        List<Integer> readsWhileOpen = new ArrayList<>();
        Mono<Void> reader = Mono.delay(Duration.ofMillis(350)).then(log.read(before, 100).collectList()).doOnNext(l -> readsWhileOpen.add(l.size())).then();

        Mono.when(late, early, reader).block(Duration.ofSeconds(20));

        assertEquals(List.of(0), readsWhileOpen, "N+1 is committed but N is not: the reader waits, it does not skip N");
        List<LoggedEvent> after = log.read(before, 100).collectList().block();
        assertEquals(2, after.size(), "once N commits the reader sees both");
        assertEquals("late", after.get(0).event().aggregateId(), "in sequence order");
        assertEquals("early", after.get(1).event().aggregateId());
    }

    @Test
    void aGapThatNothingWillFillIsPassedOnlyOnceItIsOldEnough() throws Exception {
        connect(null, Duration.ofMillis(400));
        UUID tenant = UUID.randomUUID();
        long before = log.head().block();

        // a command that rolls back after its append: the sequence number is taken and never used
        command(tenant, "rolled-back", Duration.ZERO).then(Mono.<Long>error(new IllegalStateException("fails after the append")))
                .as(tx::transactional).onErrorResume(IllegalStateException.class, e -> Mono.empty()).block();
        db.sql("SELECT nextval(pg_get_serial_sequence('cqrs_event_log', 'sequence'))").fetch().rowsUpdated().block();
        command(tenant, "after-the-gap", Duration.ZERO).block();

        assertEquals(0, log.read(before, 100).collectList().block().size(), "a young gap may still be filled: wait");
        Thread.sleep(600);
        List<LoggedEvent> later = log.read(before, 100).collectList().block();
        assertEquals(1, later.size(), "an old gap never will: the reader goes on");
        assertEquals("after-the-gap", later.get(0).event().aggregateId());
    }

    @Test
    void twoConcurrentWritersOfTheSameVersionStillGetOneWinnerWithNoLock() {
        connect(null, Duration.ofSeconds(5));
        UUID tenant = UUID.randomUUID();
        AtomicInteger conflicts = new AtomicInteger();

        List<Long> done = Flux.range(0, 12).flatMap(i -> command(tenant, "same", Duration.ZERO)
                .onErrorResume(VersionConflictException.class, e -> {
                    conflicts.incrementAndGet();
                    return Mono.empty();
                }), 12).collectList().block(Duration.ofSeconds(30));

        assertEquals(1, done.size(), "exactly one of them appended version 1");
        assertEquals(11, conflicts.get());
    }

    /** Writers and a reader at the same time: the reader ends with every event once, in order, and was never ahead of a gap. */
    @Test
    void aReaderPollingWhileManyTenantsWriteSeesEveryEventOnceInOrder() {
        connect(null, Duration.ofSeconds(5));
        long start = log.head().block();
        List<Long> seen = new ArrayList<>();
        long[] position = {start};
        Disposable poller = Flux.interval(Duration.ofMillis(5)).onBackpressureDrop().concatMap(tick -> log.read(position[0], 50).collectList())
                .subscribe(batch -> {
                    for (LoggedEvent e : batch) {
                        seen.add(e.sequence());
                        position[0] = e.sequence();
                    }
                }, failure -> System.out.println("POLLER FAILED: " + failure));

        List<Long> written = tenantsWriting(16, 25);
        Mono.delay(Duration.ofMillis(600)).block();
        poller.dispose();

        assertEquals(16 * 25, written.size());
        assertEquals(16 * 25, seen.size(), "every event was read: none was skipped behind a higher sequence");
        for (int i = 1; i < seen.size(); i++) {
            assertEquals(seen.get(i - 1) + 1, seen.get(i), "once, in order, no gap");
        }
    }

    @Test
    void aRefusedAppendLeavesNothingBehindAndTheBoundDoesNotOutliveTheCommand() {
        connect(Duration.ofMillis(50));
        UUID holder = UUID.randomUUID();
        UUID waiter = UUID.randomUUID();

        // a transaction that holds the lock, and one that has to wait for it
        Mono<Long> holding = command(holder, "h-1", Duration.ofMillis(400));
        Mono<Void> refusedOne = Mono.delay(Duration.ofMillis(100)).then(command(waiter, "w-1", Duration.ZERO)).then()
                .onErrorResume(AppendContendedException.class, e -> Mono.empty());
        Mono.when(holding, refusedOne).block(Duration.ofSeconds(20));

        Long waiterEvents = db.sql("SELECT count(*) AS n FROM cqrs_event_log WHERE tenant_id = :t").bind("t", waiter)
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertEquals(0L, waiterEvents, "the refused command's event was not written");
        // the same tenant can write at once afterwards: the bound was for that transaction only
        assertTrue(command(waiter, "w-2", Duration.ZERO).block() < 1_000);
        assertNoGapAndCommitOrder();
    }

    private void assertNoGapAndCommitOrder() {
        List<Long> sequences = new ArrayList<>();
        log.read(0, 100_000).doOnNext(e -> sequences.add(e.sequence())).blockLast();
        for (int i = 1; i < sequences.size(); i++) {
            assertEquals(sequences.get(i - 1) + 1, sequences.get(i), "no gap, ascending");
        }
    }
}
