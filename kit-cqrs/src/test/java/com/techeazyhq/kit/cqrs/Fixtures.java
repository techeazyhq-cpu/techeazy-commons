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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** A sample domain for the tests: items whose name changes, and a projection of the current name of each. */
final class Fixtures {

    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final Instant AT = Instant.parse("2026-11-28T10:00:00Z");

    private Fixtures() {
    }

    static EventRecord event(String id, long version, String type, String payload) {
        return new EventRecord(UUID.randomUUID(), TENANT, "item", id, version, type, AT, payload);
    }

    /** The current name of every item, built from ItemNamed events. */
    static final class NamesProjection implements Projection {

        final Map<String, String> names = new HashMap<>();
        int version = 1;
        int applied;

        @Override
        public String name() {
            return "names";
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public Mono<Void> apply(LoggedEvent logged) {
            applied++;
            EventRecord e = logged.event();
            if (e.type().equals("ItemRemoved")) {
                names.remove(e.aggregateId());
            } else {
                names.put(e.aggregateId(), e.payload());
            }
            return Mono.empty();
        }

        @Override
        public Mono<Void> reset() {
            names.clear();
            return Mono.empty();
        }
    }
}
