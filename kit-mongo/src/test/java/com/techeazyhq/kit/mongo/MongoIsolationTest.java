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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** STD-DB-04: isolation tests against MongoDB, the release gate. A tenant sees and changes only its own documents. */
class MongoIsolationTest {

    private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static MongoClient client;
    private MongoDatabase database;
    private TenantDocuments docs;

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

    @BeforeEach
    void collection() {
        database = client.getDatabase("t" + UUID.randomUUID().toString().replace("-", ""));
        MongoSchema.ensure(database, "items", Map.of("name", "string", "archived", "bool"), List.of("name")).block();
        docs = new TenantDocuments(database.getCollection("items"));
    }

    private void insertFor(UUID tenant, String id, String name) {
        docs.insert(tenant, id, Map.of("name", name, "archived", false)).block();
    }

    @Test
    void aTenantReadsItsOwnDocumentAndNeverAnotherTenants() {
        insertFor(A, "i-1", "Stethoscope");
        insertFor(B, "i-2", "Gloves");

        assertEquals("Stethoscope", docs.findById(A, "i-1").block().getString("name"));
        assertNull(docs.findById(A, "i-2").block(), "B's document is invisible to A");
        assertNull(docs.findById(B, "i-1").block());
        assertEquals(List.of("i-1"), docs.find(A, null, null, 100).map(d -> d.getString("id")).collectList().block());
        assertEquals(1L, docs.count(A, null).block());
    }

    @Test
    void theSameIdInTwoTenantsAreTwoDocuments() {
        insertFor(A, "same", "A's");
        insertFor(B, "same", "B's");

        assertEquals("A's", docs.findById(A, "same").block().getString("name"));
        assertEquals("B's", docs.findById(B, "same").block().getString("name"));
        StepVerifier.create(docs.insert(A, "same", Map.of("name", "again"))).expectError(MongoWriteException.class).verify();
    }

    @Test
    void aTenantCannotReplaceOrDeleteAnotherTenantsDocumentEvenWithTheSameId() {
        insertFor(B, "i-9", "B's secret");

        assertFalse(docs.replace(A, "i-9", Map.of("name", "overwritten")).block());
        assertFalse(docs.delete(A, "i-9").block());

        assertEquals("B's secret", docs.findById(B, "i-9").block().getString("name"));
    }

    @Test
    void aFilterCannotWidenTheSearchToAnotherTenant() {
        insertFor(A, "i-1", "mine");
        insertFor(B, "i-2", "theirs");

        // the caller's own filter is always ANDed with the tenant: none of these reaches B's document
        for (var filter : List.of(Filters.or(Filters.eq("tenantId", B.toString()), Filters.exists("name")),
                Filters.ne("tenantId", A.toString()), Filters.eq("tenantId", B.toString()),
                new Document("$or", List.of(new Document("tenantId", B.toString()), new Document())))) {
            List<String> ids = docs.find(A, filter, null, 100).map(d -> d.getString("id")).collectList().block();
            assertFalse(ids.contains("i-2"), filter.toString());
        }
    }

    @Test
    void aFilterThatRunsCodeIsRefused() {
        insertFor(A, "i-1", "mine");

        assertThrows(IllegalArgumentException.class, () -> docs.find(A, new Document("$where", "this.name == 'mine'"), null, 10));
        assertThrows(IllegalArgumentException.class, () -> docs.count(A, Filters.expr(new Document("$function",
                new Document("body", "function(){return true}").append("args", List.of()).append("lang", "js")))));
    }

    @Test
    void theLibrarysFieldsAndUnsafeNamesCannotBeSetByACaller() {
        for (String bad : List.of("tenantId", "id", "v", "_id", "$set", "a.b", "1abc", "")) {
            assertThrows(IllegalArgumentException.class, () -> docs.insert(A, "x", Map.of(bad, "v")).block(), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> docs.findById(A, "bad id").block());
        assertThrows(NullPointerException.class, () -> docs.findById(null, "x").block());
    }

    @Test
    void theDatabaseItselfRefusesADocumentWithNoTenantEvenFromAToolThatBypassesTheLibrary() {
        MongoCollection<Document> raw = database.getCollection("items");

        StepVerifier.create(Mono.from(raw.insertOne(new Document("id", "x").append("name", "n").append("v", 0L))))
                .expectError(MongoWriteException.class).verify();
        StepVerifier.create(Mono.from(raw.insertOne(new Document("tenantId", "not-a-uuid").append("id", "x").append("name", "n")
                .append("v", 0L)))).expectError(MongoWriteException.class).verify();
        StepVerifier.create(Mono.from(raw.insertOne(new Document("tenantId", A.toString()).append("id", "x").append("v", 0L))))
                .expectError(MongoWriteException.class).verify();
    }

    @Test
    void anOlderOrRepeatedVersionChangesNothing() {
        assertTrue(docs.upsertIfNewer(A, "i-1", 2, Map.of("name", "v2")).block());
        assertFalse(docs.upsertIfNewer(A, "i-1", 2, Map.of("name", "again")).block(), "the same version");
        assertFalse(docs.upsertIfNewer(A, "i-1", 1, Map.of("name", "old")).block(), "an older version");
        assertTrue(docs.upsertIfNewer(A, "i-1", 3, Map.of("name", "v3")).block());

        assertEquals("v3", docs.findById(A, "i-1").block().getString("name"));
        assertEquals(1L, docs.count(A, null).block());
    }

    @Test
    void manyConcurrentWritersOfTheSameDocumentLeaveTheNewestVersion() {
        Flux.range(1, 60).flatMap(v -> docs.upsertIfNewer(A, "i-1", v, Map.of("name", "v" + v)), 16).blockLast();

        assertEquals("v60", docs.findById(A, "i-1").block().getString("name"));
        assertEquals(1L, docs.count(A, null).block());
    }

    @Test
    void sortingLimitingAndErasingATenantWork() {
        for (int i = 1; i <= 5; i++) {
            insertFor(A, "i-" + i, "name-" + i);
        }
        insertFor(B, "b-1", "b");

        assertEquals(List.of("name-5", "name-4"), docs.find(A, null, Sorts.descending("name"), 2).map(d -> d.getString("name"))
                .collectList().block());
        assertEquals(5L, docs.deleteAllOf(A).block());
        assertEquals(0L, docs.count(A, null).block());
        assertEquals(1L, docs.count(B, null).block(), "erasing A leaves B");
    }

    @Test
    void maintenanceSeesEveryDocumentOfOneTenantBeyondTheServingLimitAndTheTenantsPresent() {
        for (int i = 0; i < 1100; i++) {
            insertFor(A, "i-" + i, "n");
        }
        insertFor(B, "b-1", "b");

        assertEquals(1100L, docs.findAll(A).count().block(), "no limit, unlike find");
        assertEquals(1L, docs.findAll(B).count().block(), "and only that tenant's");
        assertEquals(java.util.Set.of(A.toString(), B.toString()), new java.util.HashSet<>(docs.tenants().collectList().block()));
    }

    @Test
    void aDatabasePerTenantIsATierOfItsOwnAndStillChecksTheTenant() {
        TenantDatabases databases = new TenantDatabases(client, "ogh_t");
        TenantDocuments a = databases.documents(A, "items");
        TenantDocuments b = databases.documents(B, "items");

        a.insert(A, "i-1", Map.of("name", "A's")).block();
        b.insert(B, "i-1", Map.of("name", "B's")).block();

        assertEquals("A's", a.findById(A, "i-1").block().getString("name"));
        assertNull(a.findById(B, "i-1").block(), "another tenant's id on this tenant's database finds nothing");
        assertTrue(databases.of(A).getName().startsWith("ogh_t_"));
        assertFalse(databases.of(A).getName().equals(databases.of(B).getName()));
        assertThrows(IllegalArgumentException.class, () -> new TenantDatabases(client, "Bad Prefix"));
    }
}
