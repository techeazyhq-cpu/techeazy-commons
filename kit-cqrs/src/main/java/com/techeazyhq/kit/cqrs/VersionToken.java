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

import java.util.Optional;
import java.util.UUID;

/**
 * What a command returns so the client can read its own write: "this aggregate, at least this version". It carries ids and a
 * number only. A client passes it back with its next read, and the query side waits until its projection has reached it (or
 * says it has not, and the caller reads from the command side). Forging one can only make a read wait for the time limit.
 */
public record VersionToken(AggregateKey aggregate, long version) {

    private static final String PREFIX = "rw1";

    public VersionToken {
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1");
        }
    }

    public static VersionToken of(EventRecord event) {
        return new VersionToken(event.aggregate(), event.version());
    }

    /** {@code rw1.<tenant>.<type>.<id>.<version>}, safe in a header or a cookie. */
    public String encode() {
        return PREFIX + "." + aggregate.tenantId() + "." + aggregate.aggregateType() + "." + aggregate.aggregateId() + "." + version;
    }

    /** Strict: anything that is not a token this class produced is empty, never a guess. */
    public static Optional<VersionToken> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] parts = text.split("[.]", 4);
        if (parts.length != 4 || !PREFIX.equals(parts[0]) || parts[1].length() != 36) {
            return Optional.empty();
        }
        try {
            int last = parts[3].lastIndexOf('.');
            if (last < 1) {
                return Optional.empty();
            }
            String id = parts[3].substring(0, last);
            long version = Long.parseLong(parts[3].substring(last + 1));
            return Optional.of(new VersionToken(new AggregateKey(UUID.fromString(parts[1]), parts[2], id), version));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
