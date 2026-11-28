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

package com.techeazyhq.kit.saga;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The reference {@link SagaStore}, in memory, for tests and local runs. Thread-safe. */
public final class InMemorySagaStore implements SagaStore {

    private final Map<UUID, SagaInstance> sagas = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> insert(SagaInstance saga) {
        return Mono.fromRunnable(() -> {
            if (sagas.putIfAbsent(saga.id(), saga) != null) {
                throw new IllegalStateException("saga already exists");
            }
        });
    }

    @Override
    public Mono<SagaInstance> find(UUID id) {
        return Mono.justOrEmpty(sagas.get(id));
    }

    @Override
    public Mono<Boolean> save(SagaInstance next, long expectedVersion) {
        return Mono.fromSupplier(() -> {
            boolean[] moved = {false};
            sagas.computeIfPresent(next.id(), (id, current) -> {
                if (current.version() == expectedVersion) {
                    moved[0] = true;
                    return next;
                }
                return current;
            });
            return moved[0];
        });
    }

    @Override
    public Flux<SagaInstance> due(Instant now, int limit) {
        return select(s -> s.status() == SagaStatus.RUNNING && !s.nextAttemptAt().isAfter(now), limit);
    }

    @Override
    public Flux<SagaInstance> overdue(Instant before, int limit) {
        return select(s -> s.status() == SagaStatus.RUNNING && s.nextAttemptAt().isBefore(before), limit);
    }

    @Override
    public Flux<SagaInstance> byStatus(SagaStatus status, int limit) {
        return select(s -> s.status() == status, limit);
    }

    @Override
    public Mono<Map<String, Long>> counts() {
        return Mono.fromSupplier(() -> {
            Map<String, Long> out = new LinkedHashMap<>();
            sagas.values().forEach(s -> out.merge(s.type() + "/" + s.status(), 1L, Long::sum));
            return out;
        });
    }

    private Flux<SagaInstance> select(Predicate<SagaInstance> filter, int limit) {
        return Flux.defer(() -> Flux.fromStream(sagas.values().stream().filter(filter)
                .sorted(Comparator.comparing(SagaInstance::nextAttemptAt).thenComparing(SagaInstance::id)).limit(limit)));
    }
}
