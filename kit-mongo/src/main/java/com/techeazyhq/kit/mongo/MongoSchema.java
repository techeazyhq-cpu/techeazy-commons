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

import com.mongodb.MongoCommandException;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import reactor.core.publisher.Mono;

/**
 * Creates a collection the way {@link TenantDocuments} expects it, so the database enforces what the layer assumes
 * (STD-DB-04): a JSON Schema validator that <b>requires</b> a {@code tenantId} (a UUID string), an {@code id} and a version on
 * every document and refuses the write otherwise (so even a tool that bypasses the library cannot store a document with no
 * tenant), and a unique index on {@code (tenantId, id)}. Safe to run again.
 */
public final class MongoSchema {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private MongoSchema() {
    }

    /**
     * @param properties the collection's own fields: name to the JSON Schema bson type ({@code "string"}, {@code "bool"},
     *                   {@code "date"}...); they are optional unless listed in {@code required}
     */
    public static Mono<Void> ensure(MongoDatabase database, String collection, Map<String, String> properties, List<String> required) {
        Document props = new Document();
        props.put("tenantId", new Document("bsonType", "string").append("pattern", UUID_PATTERN));
        props.put("id", new Document("bsonType", "string").append("minLength", 1).append("maxLength", 128));
        props.put("v", new Document("bsonType", List.of("long", "int")));
        properties.forEach((name, type) -> props.put(name, new Document("bsonType", type)));
        List<String> all = new java.util.ArrayList<>(List.of("tenantId", "id", "v"));
        all.addAll(required);
        Document schema = new Document("bsonType", "object").append("required", all).append("properties", props);
        CreateCollectionOptions options = new CreateCollectionOptions().validationOptions(new ValidationOptions()
                .validator(new Document("$jsonSchema", schema)).validationLevel(ValidationLevel.STRICT)
                .validationAction(ValidationAction.ERROR));
        return Mono.from(database.createCollection(collection, options))
                .onErrorResume(MongoCommandException.class, e -> e.getErrorCode() == 48 ? Mono.empty() : Mono.error(e)) // exists
                .then(Mono.from(database.getCollection(collection).createIndex(Indexes.ascending("tenantId", "id"),
                        new IndexOptions().unique(true).name("tenant_id_unique"))))
                .then();
    }
}
