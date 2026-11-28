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

package com.techeazyhq.kit.mongo;

import com.mongodb.reactivestreams.client.MongoDatabase;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * One step of a database's schema: a version (1, 2, 3 with no gaps), a description, a {@code checksum} that changes when the step's
 * meaning changes (a version of its script, or a hash of it), and what it does (create a collection, an index, backfill a field).
 * A step that has been applied is never edited: a change is a new step, as with Flyway for PostgreSQL.
 */
public record MongoMigration(int version, String description, String checksum, Function<MongoDatabase, Mono<Void>> action) {

    public MongoMigration {
        if (version < 1) {
            throw new IllegalArgumentException("versions start at 1");
        }
        if (description == null || description.isBlank() || checksum == null || checksum.isBlank()) {
            throw new IllegalArgumentException("a migration says what it does and carries a checksum");
        }
        Objects.requireNonNull(action, "action");
    }
}
