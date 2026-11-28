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

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.util.Objects;
import java.util.UUID;

/**
 * The stronger tier of isolation (STD-DB-04, tier 2 and 3): a database of its own for a tenant, in the same cluster or another.
 * {@link TenantDocuments} is still used on top, so the tenant is checked on every call here too; a tenant moved to its own
 * database needs no change to the code that uses it.
 */
public final class TenantDatabases {

    private final MongoClient client;
    private final String prefix;

    /** @param prefix the product's own prefix for tenant databases, for example {@code ogh_t} */
    public TenantDatabases(MongoClient client, String prefix) {
        this.client = Objects.requireNonNull(client, "client");
        if (prefix == null || !prefix.matches("[a-z][a-z0-9_]{0,20}")) {
            throw new IllegalArgumentException("the prefix is lower-case letters, digits and underscores");
        }
        this.prefix = prefix;
    }

    /** The database of one tenant: {@code <prefix>_<uuid without dashes>}. */
    public MongoDatabase of(UUID tenant) {
        return client.getDatabase(prefix + "_" + Objects.requireNonNull(tenant, "tenant").toString().replace("-", ""));
    }

    public TenantDocuments documents(UUID tenant, String collection) {
        return new TenantDocuments(of(tenant).getCollection(collection));
    }
}
