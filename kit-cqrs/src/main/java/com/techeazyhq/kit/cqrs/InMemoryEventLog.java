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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The reference {@link EventLog}, in memory, for tests and local runs. Thread-safe. */
public final class InMemoryEventLog implements EventLog, PublishCursor {

    private final List<LoggedEvent> events = new ArrayList<>();
    private final Map<AggregateKey, Long> versions = new HashMap<>();
    private final Map<String, Long> cursors = new HashMap<>();

    @Override
    public synchronized Mono<Void> append(EventRecord event) {
        Objects.requireNonNull(event, "event");
        long current = versions.getOrDefault(event.aggregate(), 0L);
        if (event.version() != current + 1) {
            return Mono.error(new VersionConflictException(event.aggregate(), event.version()));
        }
        versions.put(event.aggregate(), event.version());
        events.add(new LoggedEvent(events.size() + 1L, event));
        return Mono.empty();
    }

    @Override
    public Flux<LoggedEvent> read(long after, int limit) {
        return Flux.defer(() -> {
            List<LoggedEvent> out;
            synchronized (this) {
                out = events.stream().filter(e -> e.sequence() > after).limit(limit).toList();
            }
            return Flux.fromIterable(out);
        });
    }

    @Override
    public synchronized Mono<Long> head() {
        return Mono.just((long) events.size());
    }

    @Override
    public synchronized Mono<Long> get(String relay) {
        return Mono.just(cursors.getOrDefault(relay, 0L));
    }

    @Override
    public synchronized Mono<Boolean> advance(String relay, long from, long to) {
        if (cursors.getOrDefault(relay, 0L) != from) {
            return Mono.just(false);
        }
        cursors.put(relay, to);
        return Mono.just(true);
    }
}
