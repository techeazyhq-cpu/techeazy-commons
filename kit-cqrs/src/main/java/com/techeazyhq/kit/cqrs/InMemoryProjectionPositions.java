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

import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/** The reference {@link ProjectionPositions}, in memory. Thread-safe. */
public final class InMemoryProjectionPositions implements ProjectionPositions {

    private record Key(String projection, AggregateKey aggregate) {
    }

    private final Map<Key, Long> versions = new HashMap<>();
    private final Map<String, Long> sequences = new HashMap<>();
    private final Map<String, Integer> built = new HashMap<>();

    @Override
    public synchronized Mono<Long> appliedVersion(String projection, AggregateKey key) {
        return Mono.just(versions.getOrDefault(new Key(projection, key), 0L));
    }

    @Override
    public synchronized Mono<Long> lastSequence(String projection) {
        return Mono.just(sequences.getOrDefault(projection, 0L));
    }

    @Override
    public synchronized Mono<Integer> builtWithVersion(String projection) {
        return Mono.just(built.getOrDefault(projection, 0));
    }

    @Override
    public synchronized Mono<Void> record(String projection, int projectionVersion, AggregateKey key, long version, long sequence) {
        versions.merge(new Key(projection, key), version, Math::max);
        sequences.merge(projection, sequence, Math::max);
        built.put(projection, projectionVersion);
        return Mono.empty();
    }

    @Override
    public synchronized Mono<Void> reset(String projection) {
        versions.keySet().removeIf(k -> k.projection().equals(projection));
        sequences.remove(projection);
        built.remove(projection);
        return Mono.empty();
    }
}
