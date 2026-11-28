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

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Brings a database to the latest version, like Flyway does for PostgreSQL: the steps run in order, each once, and what has run is
 * recorded in {@code _migrations} with its checksum. A step that was applied and has since changed, or an applied version the
 * service no longer knows, stops the start-up (the schema is not what the code expects). Two instances starting together cannot
 * both apply a step: the record is inserted first, under a unique index on the version, and only its writer runs the step.
 */
public final class MongoMigrations {

    private static final String COLLECTION = "_migrations";

    private final List<MongoMigration> steps;
    private final Clock clock;

    public MongoMigrations(List<MongoMigration> steps, Clock clock) {
        this.clock = clock;
        List<MongoMigration> sorted = new ArrayList<>(steps);
        sorted.sort(java.util.Comparator.comparingInt(MongoMigration::version));
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (!seen.add(sorted.get(i).version()) || sorted.get(i).version() != i + 1) {
                throw new IllegalArgumentException("migration versions are 1, 2, 3... with none missing or repeated");
            }
        }
        this.steps = List.copyOf(sorted);
    }

    /** Applies what is pending. The versions applied by this run. */
    public Mono<List<Integer>> migrate(MongoDatabase database) {
        MongoCollection<Document> history = database.getCollection(COLLECTION);
        return Mono.from(history.createIndex(Indexes.ascending("version"), new IndexOptions().unique(true)))
                .thenMany(Flux.from(history.find().sort(Sorts.ascending("version"))))
                .collectList()
                .flatMap(applied -> {
                    for (Document record : applied) {
                        int version = record.getInteger("version");
                        if (version > steps.size()) {
                            return Mono.<List<Integer>>error(new IllegalStateException(
                                    "the database has migration " + version + " that this service does not know"));
                        }
                        if (!steps.get(version - 1).checksum().equals(record.getString("checksum"))) {
                            return Mono.<List<Integer>>error(new IllegalStateException(
                                    "migration " + version + " was changed after it was applied: write a new one"));
                        }
                    }
                    return Flux.fromIterable(steps.subList(Math.min(applied.size(), steps.size()), steps.size()))
                            .concatMap(step -> claim(history, step).flatMap(won -> won
                                    ? step.action().apply(database).thenReturn(step.version()) : Mono.empty()))
                            .collectList();
                });
    }

    /** Records the step before running it: only the instance whose insert wins runs it. */
    private Mono<Boolean> claim(MongoCollection<Document> history, MongoMigration step) {
        Document record = new Document("version", step.version()).append("description", step.description())
                .append("checksum", step.checksum()).append("appliedAt", java.util.Date.from(clock.instant()));
        return Mono.from(history.insertOne(record)).thenReturn(true)
                .onErrorResume(com.mongodb.MongoWriteException.class, e -> e.getError().getCode() == 11000
                        ? Mono.just(false) : Mono.error(e));
    }
}
