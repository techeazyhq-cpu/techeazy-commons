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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class MongoMigrationsTest {

    private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
    private static MongoClient client;

    @BeforeAll
    static void start() {
        MONGO.start();
        client = MongoClients.create(MONGO.getConnectionString());
    }

    @AfterAll
    static void stop() {
        client.close();
        MONGO.stop();
    }

    private static MongoDatabase fresh() {
        return client.getDatabase("m" + UUID.randomUUID().toString().replace("-", ""));
    }

    private final List<String> ran = new CopyOnWriteArrayList<>();

    private MongoMigration step(int version, String checksum) {
        return new MongoMigration(version, "step " + version, checksum, db -> {
            ran.add("v" + version);
            return Mono.from(db.createCollection("c" + version)).then();
        });
    }

    @Test
    void pendingStepsRunInOrderOnceAndAreRecorded() {
        MongoDatabase db = fresh();
        MongoMigrations migrations = new MongoMigrations(List.of(step(2, "b"), step(1, "a")), Clock.systemUTC());

        assertEquals(List.of(1, 2), migrations.migrate(db).block());
        assertEquals(List.of("v1", "v2"), ran);

        assertEquals(List.of(), migrations.migrate(db).block(), "nothing is pending the second time");
        assertEquals(2, ran.size());
        assertEquals(2L, Flux.from(db.getCollection("_migrations").find()).count().block());
    }

    @Test
    void aNewStepRunsAloneWhenTheOldOnesAreDone() {
        MongoDatabase db = fresh();
        new MongoMigrations(List.of(step(1, "a")), Clock.systemUTC()).migrate(db).block();

        assertEquals(List.of(2), new MongoMigrations(List.of(step(1, "a"), step(2, "b")), Clock.systemUTC()).migrate(db).block());
        assertEquals(List.of("v1", "v2"), ran);
    }

    @Test
    void aStepChangedAfterItWasAppliedStopsTheStartUp() {
        MongoDatabase db = fresh();
        new MongoMigrations(List.of(step(1, "a")), Clock.systemUTC()).migrate(db).block();

        StepVerifier.create(new MongoMigrations(List.of(step(1, "edited")), Clock.systemUTC()).migrate(db))
                .expectErrorSatisfies(e -> assertTrue(e.getMessage().contains("changed after it was applied"))).verify();
    }

    @Test
    void aDatabaseAheadOfTheServiceStopsTheStartUp() {
        MongoDatabase db = fresh();
        new MongoMigrations(List.of(step(1, "a"), step(2, "b")), Clock.systemUTC()).migrate(db).block();

        StepVerifier.create(new MongoMigrations(List.of(step(1, "a")), Clock.systemUTC()).migrate(db))
                .expectErrorSatisfies(e -> assertTrue(e.getMessage().contains("does not know"))).verify();
    }

    @Test
    void twoInstancesStartingTogetherApplyEachStepOnce() {
        MongoDatabase db = fresh();
        MongoMigrations migrations = new MongoMigrations(List.of(step(1, "a"), step(2, "b"), step(3, "c")), Clock.systemUTC());

        List<List<Integer>> results = Flux.range(0, 4).flatMap(i -> migrations.migrate(db)).collectList().block();

        assertEquals(3, ran.size(), "each step ran once: " + ran);
        assertEquals(3, results.stream().mapToInt(List::size).sum());
    }

    @Test
    void versionsHaveNoGapsOrRepeats() {
        assertThrows(IllegalArgumentException.class, () -> new MongoMigrations(List.of(step(1, "a"), step(3, "c")), Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new MongoMigrations(List.of(step(1, "a"), step(1, "a")), Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new MongoMigration(0, "x", "c", db -> Mono.empty()));
        assertThrows(IllegalArgumentException.class, () -> new MongoMigration(1, " ", "c", db -> Mono.empty()));
    }
}
