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

package com.techeazyhq.kit.cqrs;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One thing that happened to an aggregate, as it is written to the log in the same transaction as the change: which aggregate
 * (type, id, tenant), which version of it this made, what kind of event, when, and a small JSON payload. Events are <b>thin</b>:
 * ids and facts that are safe to publish, never the sensitive values themselves (a consumer that needs them asks the owner).
 *
 * <p>{@code version} counts the aggregate's changes from 1 with no gaps: it is what lets a projection ignore a duplicate, notice a
 * missing event, and lets a client wait for the change it made.
 */
public record EventRecord(UUID id, UUID tenantId, String aggregateType, String aggregateId, long version, String type,
                          Instant occurredAt, String payload) {

    public static final int MAX_PAYLOAD = 16 * 1024;

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");
    private static final Pattern AGGREGATE_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9:._-]{1,128}");

    public EventRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        if (aggregateType == null || !AGGREGATE_TYPE.matcher(aggregateType).matches()) {
            throw new IllegalArgumentException("aggregateType must be a plain name with no dot");
        }
        if (aggregateId == null || !ID.matcher(aggregateId).matches()) {
            throw new IllegalArgumentException("aggregateId must be a plain id");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1");
        }
        if (type == null || !NAME.matcher(type).matches()) {
            throw new IllegalArgumentException("type must be a plain name");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (payload == null || payload.length() > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload is JSON text of at most " + MAX_PAYLOAD + " characters");
        }
    }

    /** The aggregate this event belongs to, as one key. */
    public AggregateKey aggregate() {
        return new AggregateKey(tenantId, aggregateType, aggregateId);
    }
}
