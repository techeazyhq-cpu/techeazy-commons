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

import com.techeazyhq.kit.cqrs.AggregateKey;
import com.techeazyhq.kit.cqrs.ProjectionPositions;
import java.util.Objects;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * The PostgreSQL {@link ProjectionPositions}. Use it with a projection stored in the same database and the runner's
 * {@code atomically} set to {@code TransactionalOperator::transactional}, so the projection row and its position commit together.
 */
public final class R2dbcProjectionPositions implements ProjectionPositions {

    private final DatabaseClient db;

    public R2dbcProjectionPositions(DatabaseClient db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public Mono<Long> appliedVersion(String projection, AggregateKey key) {
        return db.sql("""
                SELECT version FROM cqrs_projection_position
                WHERE projection = :p AND tenant_id = :t AND aggregate_type = :at AND aggregate_id = :ai
                """).bind("p", projection).bind("t", key.tenantId()).bind("at", key.aggregateType()).bind("ai", key.aggregateId())
                .map((row, meta) -> row.get("version", Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> lastSequence(String projection) {
        return db.sql("SELECT last_sequence FROM cqrs_projection_state WHERE projection = :p").bind("p", projection)
                .map((row, meta) -> row.get("last_sequence", Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<Integer> builtWithVersion(String projection) {
        return db.sql("SELECT built_with FROM cqrs_projection_state WHERE projection = :p").bind("p", projection)
                .map((row, meta) -> row.get("built_with", Integer.class)).one().defaultIfEmpty(0);
    }

    @Override
    public Mono<Void> record(String projection, int projectionVersion, AggregateKey key, long version, long sequence) {
        return db.sql("""
                INSERT INTO cqrs_projection_position (projection, tenant_id, aggregate_type, aggregate_id, version)
                VALUES (:p, :t, :at, :ai, :v)
                ON CONFLICT (projection, tenant_id, aggregate_type, aggregate_id) DO UPDATE SET version = GREATEST(cqrs_projection_position.version, :v)
                """).bind("p", projection).bind("t", key.tenantId()).bind("at", key.aggregateType()).bind("ai", key.aggregateId())
                .bind("v", version).fetch().rowsUpdated()
                .then(db.sql("""
                        INSERT INTO cqrs_projection_state (projection, last_sequence, built_with) VALUES (:p, :s, :b)
                        ON CONFLICT (projection) DO UPDATE SET last_sequence = GREATEST(cqrs_projection_state.last_sequence, :s), built_with = :b
                        """).bind("p", projection).bind("s", sequence).bind("b", projectionVersion).fetch().rowsUpdated())
                .then();
    }

    @Override
    public Mono<Void> reset(String projection) {
        return db.sql("DELETE FROM cqrs_projection_position WHERE projection = :p").bind("p", projection).fetch().rowsUpdated()
                .then(db.sql("DELETE FROM cqrs_projection_state WHERE projection = :p").bind("p", projection).fetch().rowsUpdated())
                .then();
    }
}
