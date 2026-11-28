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

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.reactivestreams.client.MongoCollection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * One collection, seen through one tenant at a time. MongoDB has no row-level security, so this is where tenant isolation is made
 * (STD-DB-04): <b>every method takes the tenant</b>, there is no method without it, and the underlying collection is never
 * handed out. A document is stored with its {@code tenantId} and an {@code id}; a read, an update or a delete is always the
 * caller's filter <b>and</b> the tenant, so another tenant's document is invisible and untouchable even with the same id.
 *
 * <ul>
 *   <li>A caller's filter that could run code ({@code $where}, {@code $function}, {@code $accumulator}) is refused.</li>
 *   <li>Field names are plain: no {@code $}, no {@code .}, and the library's own names ({@code tenantId}, {@code id},
 *       {@code v}, {@code _id}) cannot be set by a caller.</li>
 *   <li>{@link #upsertIfNewer} writes a read model idempotently: a stale or repeated version changes nothing.</li>
 * </ul>
 * Create the collection with {@link MongoSchema#ensure} so the database itself also refuses a document without a tenant.
 */
public final class TenantDocuments {

    static final String TENANT = "tenantId";
    static final String ID = "id";
    static final String VERSION = "v";

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9:._-]{1,128}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final String[] FORBIDDEN_OPERATORS = {"$where", "$function", "$accumulator"};

    private final MongoCollection<Document> collection;

    public TenantDocuments(MongoCollection<Document> collection) {
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    /** Adds a document for the tenant. A second document with the same id for the same tenant is an error. */
    public Mono<Void> insert(UUID tenant, String id, Map<String, Object> fields) {
        Document document = document(tenant, id, 0L, fields); // a programming error throws here, not when subscribed
        return Mono.defer(() -> Mono.from(collection.insertOne(document))).then();
    }

    /** The document, or empty if this tenant has none with that id. */
    public Mono<Document> findById(UUID tenant, String id) {
        Bson filter = Filters.and(tenantFilter(tenant), Filters.eq(ID, checkedId(id)));
        return Mono.defer(() -> Mono.from(collection.find(filter).first()));
    }

    /** The tenant's documents that match {@code filter} (null for all of them), at most {@code limit}. */
    public Flux<Document> find(UUID tenant, Bson filter, Bson sort, int limit) {
        Bson scoped = scoped(tenant, filter);
        return Flux.defer(() -> {
            var query = collection.find(scoped).limit(Math.max(1, Math.min(limit, 1000)));
            return Flux.from(sort == null ? query : query.sort(sort));
        });
    }

    /**
     * Every document of one tenant, with no limit and in no particular order: for maintenance that must see the whole of it
     * (reconciling a read model with its source, an export), never for serving a screen.
     */
    public Flux<Document> findAll(UUID tenant) {
        Bson filter = tenantFilter(tenant);
        return Flux.defer(() -> Flux.from(collection.find(filter)));
    }

    /** The tenants that have at least one document, for maintenance that goes tenant by tenant. */
    public Flux<String> tenants() {
        return Flux.defer(() -> Flux.from(collection.distinct(TENANT, String.class)));
    }

    public Mono<Long> count(UUID tenant, Bson filter) {
        Bson scoped = scoped(tenant, filter);
        return Mono.defer(() -> Mono.from(collection.countDocuments(scoped)));
    }

    /**
     * Writes the document if there is none, or if the stored one has an older version; returns true when it wrote. A repeated or
     * older event changes nothing, which is what lets a projection be applied twice and rebuilt.
     */
    public Mono<Boolean> upsertIfNewer(UUID tenant, String id, long version, Map<String, Object> fields) {
        Document replacement = document(tenant, id, version, fields);
        Bson filter = Filters.and(tenantFilter(tenant), Filters.eq(ID, id), Filters.lt(VERSION, version));
        return Mono.defer(() -> {
            // the stored version is lower: replace it; or there is none: insert (a duplicate key means another writer got there
            // with a version at least as new, so nothing is written)
            return Mono.from(collection.replaceOne(filter, replacement)).flatMap(result -> {
                if (result.getModifiedCount() > 0) {
                    return Mono.just(true);
                }
                return Mono.from(collection.insertOne(replacement)).thenReturn(true)
                        .onErrorResume(com.mongodb.MongoWriteException.class, e -> e.getError().getCode() == 11000
                                ? Mono.just(false) : Mono.error(e));
            });
        });
    }

    /** Replaces the document of this tenant; false if the tenant has none with that id. */
    public Mono<Boolean> replace(UUID tenant, String id, Map<String, Object> fields) {
        Bson filter = Filters.and(tenantFilter(tenant), Filters.eq(ID, checkedId(id)));
        Document replacement = document(tenant, id, 0L, fields);
        return Mono.defer(() -> Mono.from(collection.replaceOne(filter, replacement, new ReplaceOptions().upsert(false)))
                .map(r -> r.getMatchedCount() > 0));
    }

    public Mono<Boolean> delete(UUID tenant, String id) {
        Bson filter = Filters.and(tenantFilter(tenant), Filters.eq(ID, checkedId(id)));
        return Mono.defer(() -> Mono.from(collection.deleteOne(filter)).map(r -> r.getDeletedCount() > 0));
    }

    /** Everything of one tenant, for an erasure. Returns how many documents. */
    public Mono<Long> deleteAllOf(UUID tenant) {
        Bson filter = tenantFilter(tenant);
        return Mono.defer(() -> Mono.from(collection.deleteMany(filter)).map(r -> r.getDeletedCount()));
    }

    // ---- the one place a filter is built -----------------------------------------------------------------------------------------

    private static Bson tenantFilter(UUID tenant) {
        return Filters.eq(TENANT, Objects.requireNonNull(tenant, "tenant").toString());
    }

    private Bson scoped(UUID tenant, Bson filter) {
        if (filter == null) {
            return tenantFilter(tenant);
        }
        String json = filter.toBsonDocument().toJson();
        for (String operator : FORBIDDEN_OPERATORS) {
            if (json.contains(operator)) {
                throw new IllegalArgumentException("a filter may not use " + operator);
            }
        }
        return Filters.and(tenantFilter(tenant), filter);
    }

    private static String checkedId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be a plain id");
        }
        return id;
    }

    private static Document document(UUID tenant, String id, long version, Map<String, Object> fields) {
        Document document = new Document();
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            String name = field.getKey();
            if (!FIELD.matcher(name).matches() || name.equals(TENANT) || name.equals(ID) || name.equals(VERSION)) {
                throw new IllegalArgumentException("a field name is plain letters, digits and underscores, and not one the library owns: "
                        + name);
            }
            document.put(name, field.getValue());
        }
        document.put(TENANT, Objects.requireNonNull(tenant, "tenant").toString());
        document.put(ID, checkedId(id));
        document.put(VERSION, version);
        return document;
    }
}
