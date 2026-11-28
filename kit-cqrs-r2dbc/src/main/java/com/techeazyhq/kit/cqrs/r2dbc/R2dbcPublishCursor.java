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

import com.techeazyhq.kit.cqrs.PublishCursor;
import java.util.Objects;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/** The PostgreSQL {@link PublishCursor}: a compare-and-set on one row per relay. */
public final class R2dbcPublishCursor implements PublishCursor {

    private final DatabaseClient db;

    public R2dbcPublishCursor(DatabaseClient db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public Mono<Long> get(String relay) {
        return db.sql("SELECT position FROM cqrs_publish_cursor WHERE relay = :relay").bind("relay", relay)
                .map((row, meta) -> row.get("position", Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<Boolean> advance(String relay, long from, long to) {
        if (from == 0) {
            return db.sql("INSERT INTO cqrs_publish_cursor (relay, position) VALUES (:relay, :to) ON CONFLICT (relay) DO NOTHING")
                    .bind("relay", relay).bind("to", to).fetch().rowsUpdated().map(rows -> rows == 1);
        }
        return db.sql("UPDATE cqrs_publish_cursor SET position = :to WHERE relay = :relay AND position = :from")
                .bind("relay", relay).bind("from", from).bind("to", to).fetch().rowsUpdated().map(rows -> rows == 1);
    }
}
