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

import com.techeazyhq.kit.cqrs.AppendContendedException;
import com.techeazyhq.kit.cqrs.EventLog;
import com.techeazyhq.kit.cqrs.EventRecord;
import com.techeazyhq.kit.cqrs.LoggedEvent;
import com.techeazyhq.kit.cqrs.VersionConflictException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The PostgreSQL {@link EventLog}. {@link #append} runs on the caller's transaction (it is called inside the
 * {@code TransactionalOperator} that changes the aggregate), so the change and its event commit or roll back together.
 *
 * <p><b>Order.</b> Appends are serialised by a transaction-scoped advisory lock taken inside {@code append}: the next appender
 * waits for the previous commit, so the log's sequence is also the commit order, and a reader that has seen sequence N has
 * seen every event below it (no event is committed late behind a higher one). The cost is that only one appending transaction
 * commits at a time: call {@code append} as the <b>last statement</b> of the command, so the lock is held as short a time as
 * possible. {@code version} must be the aggregate's next one; a second writer of the same version, or a gap, is a
 * {@link VersionConflictException}.
 *
 * <p><b>Concurrent appends ({@link #concurrent}).</b> The lock makes every writer of every tenant wait for the commit of the one
 * before it: the throughput of the whole log is one commit at a time, and a slow commit delays everybody (#616). In the concurrent
 * mode there is no lock and appends do not wait for each other. The reading guarantee is kept on the <i>read</i> side instead: a
 * reader never moves past a gap in the sequence unless the gap is older than {@code gapTolerance}. A gap is either a transaction
 * that has taken its sequence number but not committed yet (it will fill the gap, so the reader waits) or one that rolled back
 * (it never will, so after the tolerance the reader goes on). {@code gapTolerance} must therefore be longer than the longest
 * transaction that appends (bound it with a request time budget and the database's idle_in_transaction_session_timeout); a
 * rollback after the append delays readers by that tolerance, which is why the append stays the last statement. Version
 * conflicts are then detected by the table's unique key instead of by the lock.
 */
public final class R2dbcEventLog implements EventLog {

    /** An arbitrary constant for this library's advisory lock. */
    private static final long LOCK_KEY = 0x6B6974_43515253L;

    private final DatabaseClient db;
    private final Duration lockWait;
    private final Duration gapTolerance;

    /** Waits for the order of the log for as long as it takes. */
    public R2dbcEventLog(DatabaseClient db) {
        this(db, null);
    }

    /**
     * Waits for the order of the log at most {@code lockWait}: a command that would wait longer is refused with an
     * {@link AppendContendedException} (its transaction rolls back, and it can be retried) instead of queueing behind a slow
     * neighbour for ever. Null waits without a bound.
     */
    public R2dbcEventLog(DatabaseClient db, Duration lockWait) {
        this(db, lockWait, null);
    }

    /**
     * No lock: appends of every tenant run at the same time, and a reader waits at a gap in the sequence for at most
     * {@code gapTolerance} (see the class comment). The table needs the {@code appended_at} column of {@link CqrsSchema#DDL}.
     */
    public static R2dbcEventLog concurrent(DatabaseClient db, Duration gapTolerance) {
        return new R2dbcEventLog(db, null, Objects.requireNonNull(gapTolerance, "gapTolerance"));
    }

    private R2dbcEventLog(DatabaseClient db, Duration lockWait, Duration gapTolerance) {
        this.db = Objects.requireNonNull(db, "db");
        if (lockWait != null && (lockWait.isNegative() || lockWait.isZero())) {
            throw new IllegalArgumentException("lockWait must be positive, or null for no bound");
        }
        if (gapTolerance != null && (gapTolerance.isNegative() || gapTolerance.isZero())) {
            throw new IllegalArgumentException("gapTolerance must be positive");
        }
        this.lockWait = lockWait;
        this.gapTolerance = gapTolerance;
    }

    /** Takes the lock that makes the sequence the commit order; with a bound, the wait is limited for this transaction only. */
    private Mono<Void> lock() {
        if (gapTolerance != null) {
            return Mono.empty();
        }
        Mono<Void> take = db.sql("SELECT pg_advisory_xact_lock(:key)").bind("key", LOCK_KEY).fetch().rowsUpdated().then();
        if (lockWait == null) {
            return take;
        }
        return db.sql("SELECT set_config('lock_timeout', :ms, true)").bind("ms", lockWait.toMillis() + "ms").fetch().rowsUpdated()
                .then(take)
                .then(db.sql("SELECT set_config('lock_timeout', '0', true)").fetch().rowsUpdated().then())
                .onErrorMap(R2dbcEventLog::isLockTimeout, AppendContendedException::new);
    }

    /** PostgreSQL's lock_not_available (55P03), as the driver or Spring's translation reports it. */
    private static boolean isLockTimeout(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof org.springframework.dao.CannotAcquireLockException) {
                return true;
            }
            if (c instanceof io.r2dbc.spi.R2dbcException r2dbc && "55P03".equals(r2dbc.getSqlState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Mono<Void> append(EventRecord event) {
        Objects.requireNonNull(event, "event");
        return lock().then(
                db.sql("""
                        INSERT INTO cqrs_event_log (id, tenant_id, aggregate_type, aggregate_id, version, type, occurred_at, payload)
                        SELECT :id, :tenant, :atype, :aid, :version, :type, :at, :payload
                        WHERE COALESCE((SELECT MAX(version) FROM cqrs_event_log
                                        WHERE tenant_id = :tenant AND aggregate_type = :atype AND aggregate_id = :aid), 0) = :previous
                        """)
                        .bind("id", event.id()).bind("tenant", event.tenantId()).bind("atype", event.aggregateType())
                        .bind("aid", event.aggregateId()).bind("version", event.version()).bind("type", event.type())
                        .bind("at", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)).bind("payload", event.payload())
                        .bind("previous", event.version() - 1)
                        .fetch().rowsUpdated())
                .flatMap(rows -> rows == 1 ? Mono.<Void>empty()
                        : Mono.<Void>error(new VersionConflictException(event.aggregate(), event.version())))
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> new VersionConflictException(event.aggregate(), event.version()));
    }

    @Override
    public Flux<LoggedEvent> read(long after, int limit) {
        Flux<Row> rows = db.sql("""
                SELECT sequence, id, tenant_id, aggregate_type, aggregate_id, version, type, occurred_at, payload,
                       (EXTRACT(EPOCH FROM (clock_timestamp() - appended_at)) * 1000)::bigint AS age_ms
                FROM cqrs_event_log WHERE sequence > :after ORDER BY sequence LIMIT :limit
                """)
                .bind("after", after).bind("limit", limit)
                .map((row, meta) -> new Row(new LoggedEvent(row.get("sequence", Long.class), new EventRecord(
                        row.get("id", UUID.class), row.get("tenant_id", UUID.class), row.get("aggregate_type", String.class),
                        row.get("aggregate_id", String.class), row.get("version", Long.class), row.get("type", String.class),
                        row.get("occurred_at", OffsetDateTime.class).toInstant(), row.get("payload", String.class))),
                        row.get("age_ms", Long.class)))
                .all();
        if (gapTolerance == null) {
            return rows.map(Row::event);
        }
        return rows.collectList().flatMapMany(list -> Flux.fromIterable(withoutYoungGaps(after, list)));
    }

    private record Row(LoggedEvent event, long ageMillis) {
    }

    /**
     * The events up to the first gap that might still be filled. A gap before a row is final when that row is older than the
     * tolerance: whoever took the missing number has had that long to commit and did not.
     */
    private List<LoggedEvent> withoutYoungGaps(long after, List<Row> rows) {
        List<LoggedEvent> readable = new ArrayList<>();
        long expected = after + 1;
        for (Row row : rows) {
            if (row.event().sequence() != expected && row.ageMillis() < gapTolerance.toMillis()) {
                break;
            }
            readable.add(row.event());
            expected = row.event().sequence() + 1;
        }
        return readable;
    }

    @Override
    public Mono<Long> head() {
        return db.sql("SELECT COALESCE(MAX(sequence), 0) AS head FROM cqrs_event_log")
                .map((row, meta) -> row.get("head", Long.class)).one();
    }
}
