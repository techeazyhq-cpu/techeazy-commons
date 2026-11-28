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

/**
 * The table the store uses. A product puts this in its own migration, in its own schema, and adds what its standards require
 * (the data-class tags, row-level security on {@code tenant_id}, a role for the engine). {@code data} holds ids and short facts
 * only, so it is not sensitive data; it is shown on the dashboard.
 */
public final class SagaSchema {

    public static final String DDL = """
            CREATE TABLE saga_instance (
                id              uuid        PRIMARY KEY,
                tenant_id       uuid        NOT NULL,
                type            text        NOT NULL,
                correlation_id  text        NOT NULL,
                status          text        NOT NULL,
                phase           text        NOT NULL,
                step_index      int         NOT NULL,
                attempt         int         NOT NULL,
                in_flight       boolean     NOT NULL,
                next_attempt_at timestamptz NOT NULL,
                data            text        NOT NULL,
                last_error      text,
                owner           text        NOT NULL,
                version         bigint      NOT NULL,
                created_at      timestamptz NOT NULL,
                updated_at      timestamptz NOT NULL
            );
            CREATE INDEX saga_instance_due ON saga_instance (next_attempt_at) WHERE status = 'RUNNING';
            CREATE INDEX saga_instance_status ON saga_instance (status);
            """;

    private SagaSchema() {
    }
}
