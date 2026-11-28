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

import com.techeazyhq.kit.saga.SagaInstance;
import com.techeazyhq.kit.saga.SagaPhase;
import com.techeazyhq.kit.saga.SagaStatus;
import com.techeazyhq.kit.saga.SagaStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The PostgreSQL {@link SagaStore}. Every method is one statement: nothing here opens a transaction, so no transaction is ever
 * held while a step calls another service (STD-SAGA-03). {@link #save} is {@code UPDATE ... WHERE version = :expected}.
 */
public final class R2dbcSagaStore implements SagaStore {

    private static final String COLUMNS = "id, tenant_id, type, correlation_id, status, phase, step_index, attempt, in_flight, "
            + "next_attempt_at, data, last_error, owner, version, created_at, updated_at";

    private final DatabaseClient db;

    public R2dbcSagaStore(DatabaseClient db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public Mono<Void> insert(SagaInstance s) {
        return bindAll(db.sql("INSERT INTO saga_instance (" + COLUMNS + ") VALUES (:id, :tenant, :type, :corr, :status, :phase, "
                + ":step, :attempt, :inflight, :next, :data, :error, :owner, :version, :created, :updated)"), s)
                .fetch().rowsUpdated().then();
    }

    @Override
    public Mono<SagaInstance> find(UUID id) {
        return db.sql("SELECT " + COLUMNS + " FROM saga_instance WHERE id = :id").bind("id", id).map((row, meta) -> read(row)).one();
    }

    @Override
    public Mono<Boolean> save(SagaInstance s, long expectedVersion) {
        return bindChanging(db.sql("""
                UPDATE saga_instance SET status = :status, phase = :phase, step_index = :step, attempt = :attempt,
                    in_flight = :inflight, next_attempt_at = :next, data = :data, last_error = :error, version = :version,
                    updated_at = :updated
                WHERE id = :id AND version = :expected
                """).bind("expected", expectedVersion), s).fetch().rowsUpdated().map(rows -> rows == 1);
    }

    @Override
    public Flux<SagaInstance> due(Instant now, int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM saga_instance WHERE status = 'RUNNING' AND next_attempt_at <= :now "
                + "ORDER BY next_attempt_at, id LIMIT :limit")
                .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("limit", limit).map((row, meta) -> read(row)).all();
    }

    @Override
    public Flux<SagaInstance> overdue(Instant before, int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM saga_instance WHERE status = 'RUNNING' AND next_attempt_at < :before "
                + "ORDER BY next_attempt_at, id LIMIT :limit")
                .bind("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC)).bind("limit", limit)
                .map((row, meta) -> read(row)).all();
    }

    @Override
    public Flux<SagaInstance> byStatus(SagaStatus status, int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM saga_instance WHERE status = :status ORDER BY next_attempt_at, id LIMIT :limit")
                .bind("status", status.name()).bind("limit", limit).map((row, meta) -> read(row)).all();
    }

    @Override
    public Mono<Map<String, Long>> counts() {
        return db.sql("SELECT type, status, count(*) AS n FROM saga_instance GROUP BY type, status ORDER BY type, status")
                .map((row, meta) -> Map.entry(row.get("type", String.class) + "/" + row.get("status", String.class),
                        row.get("n", Long.class)))
                .all().collectList().map(entries -> {
                    Map<String, Long> out = new LinkedHashMap<>();
                    entries.forEach(e -> out.put(e.getKey(), e.getValue()));
                    return out;
                });
    }

    private static DatabaseClient.GenericExecuteSpec bindChanging(DatabaseClient.GenericExecuteSpec spec, SagaInstance s) {
        return spec.bind("id", s.id()).bind("status", s.status().name()).bind("phase", s.phase().name())
                .bind("step", s.stepIndex()).bind("attempt", s.attempt()).bind("inflight", s.inFlight())
                .bind("next", OffsetDateTime.ofInstant(s.nextAttemptAt(), ZoneOffset.UTC))
                .bind("data", StringMapJson.write(s.data())).bind("version", s.version())
                .bind("updated", OffsetDateTime.ofInstant(s.updatedAt(), ZoneOffset.UTC))
                .bind("error", s.lastError() == null ? "" : s.lastError());
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, SagaInstance s) {
        return bindChanging(spec, s).bind("tenant", s.tenantId()).bind("type", s.type()).bind("corr", s.correlationId())
                .bind("owner", s.owner()).bind("created", OffsetDateTime.ofInstant(s.createdAt(), ZoneOffset.UTC));
    }

    private static SagaInstance read(io.r2dbc.spi.Readable row) {
        String error = row.get("last_error", String.class);
        return new SagaInstance(row.get("id", UUID.class), row.get("tenant_id", UUID.class), row.get("type", String.class),
                row.get("correlation_id", String.class), SagaStatus.valueOf(row.get("status", String.class)),
                SagaPhase.valueOf(row.get("phase", String.class)), row.get("step_index", Integer.class),
                row.get("attempt", Integer.class), row.get("in_flight", Boolean.class),
                row.get("next_attempt_at", OffsetDateTime.class).toInstant(),
                StringMapJson.read(row.get("data", String.class)), error == null || error.isEmpty() ? null : error,
                row.get("owner", String.class), row.get("version", Long.class),
                row.get("created_at", OffsetDateTime.class).toInstant(), row.get("updated_at", OffsetDateTime.class).toInstant());
    }
}
