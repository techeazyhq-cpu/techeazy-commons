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

/**
 * The tables the adapters use. A product puts this in its own migration, in its own schema, next to its aggregates, and adds
 * what its standards require (row-level security on {@code tenant_id}, column tags, a database role for the relay that may read
 * across tenants). The library creates nothing by itself.
 */
public final class CqrsSchema {

    public static final String DDL = """
            CREATE TABLE cqrs_event_log (
                sequence       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                id             uuid        NOT NULL UNIQUE,
                tenant_id      uuid        NOT NULL,
                aggregate_type text        NOT NULL,
                aggregate_id   text        NOT NULL,
                version        bigint      NOT NULL CHECK (version >= 1),
                type           text        NOT NULL,
                occurred_at    timestamptz NOT NULL,
                payload        text        NOT NULL,
                appended_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
                UNIQUE (tenant_id, aggregate_type, aggregate_id, version)
            );
            CREATE TABLE cqrs_publish_cursor (
                relay    text   PRIMARY KEY,
                position bigint NOT NULL
            );
            CREATE TABLE cqrs_projection_state (
                projection    text   PRIMARY KEY,
                last_sequence bigint NOT NULL,
                built_with    int    NOT NULL
            );
            CREATE TABLE cqrs_projection_position (
                projection     text   NOT NULL,
                tenant_id      uuid   NOT NULL,
                aggregate_type text   NOT NULL,
                aggregate_id   text   NOT NULL,
                version        bigint NOT NULL,
                PRIMARY KEY (projection, tenant_id, aggregate_type, aggregate_id)
            );
            """;

    /**
     * For a log created before {@code appended_at} existed (it lets a reader tell a gap that may still be filled from one that
     * never will: {@link R2dbcEventLog#concurrent}). Existing rows get the time of the migration, old enough for any tolerance by
     * the time the concurrent mode is used.
     */
    public static final String ADD_APPENDED_AT =
            "ALTER TABLE cqrs_event_log ADD COLUMN IF NOT EXISTS appended_at timestamptz NOT NULL DEFAULT clock_timestamp()";

    private CqrsSchema() {
    }
}
