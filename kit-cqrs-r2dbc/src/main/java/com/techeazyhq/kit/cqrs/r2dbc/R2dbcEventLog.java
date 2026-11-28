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

import com.techeazyhq.kit.cqrs.EventLog;
import com.techeazyhq.kit.cqrs.EventRecord;
import com.techeazyhq.kit.cqrs.LoggedEvent;
import com.techeazyhq.kit.cqrs.VersionConflictException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 */
public final class R2dbcEventLog implements EventLog {

    /** An arbitrary constant for this library's advisory lock. */
    private static final long LOCK_KEY = 0x6B6974_43515253L;

    private final DatabaseClient db;

    public R2dbcEventLog(DatabaseClient db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public Mono<Void> append(EventRecord event) {
        Objects.requireNonNull(event, "event");
        return db.sql("SELECT pg_advisory_xact_lock(:key)").bind("key", LOCK_KEY).fetch().rowsUpdated().then(
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
        return db.sql("""
                SELECT sequence, id, tenant_id, aggregate_type, aggregate_id, version, type, occurred_at, payload
                FROM cqrs_event_log WHERE sequence > :after ORDER BY sequence LIMIT :limit
                """)
                .bind("after", after).bind("limit", limit)
                .map((row, meta) -> new LoggedEvent(row.get("sequence", Long.class), new EventRecord(
                        row.get("id", UUID.class), row.get("tenant_id", UUID.class), row.get("aggregate_type", String.class),
                        row.get("aggregate_id", String.class), row.get("version", Long.class), row.get("type", String.class),
                        row.get("occurred_at", OffsetDateTime.class).toInstant(), row.get("payload", String.class))))
                .all();
    }

    @Override
    public Mono<Long> head() {
        return db.sql("SELECT COALESCE(MAX(sequence), 0) AS head FROM cqrs_event_log")
                .map((row, meta) -> row.get("head", Long.class)).one();
    }
}
