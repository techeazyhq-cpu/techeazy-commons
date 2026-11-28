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
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Where sagas are persisted. Every operation is one short statement: the engine never holds a transaction while it calls
 * another service. {@link #save} is compare-and-set on the version, which is what makes two workers (or a worker and a signal)
 * unable to both move a saga.
 */
public interface SagaStore {

    Mono<Void> insert(SagaInstance saga);

    Mono<SagaInstance> find(UUID id);

    /** Replaces the saga if its stored version is {@code expectedVersion}; false if someone else moved it first. */
    Mono<Boolean> save(SagaInstance next, long expectedVersion);

    /** Running sagas whose {@code nextAttemptAt} is not after {@code now}, oldest first. */
    Flux<SagaInstance> due(Instant now, int limit);

    /** Running sagas nobody has moved since before {@code before}: they should have been picked up by now. */
    Flux<SagaInstance> overdue(Instant before, int limit);

    Flux<SagaInstance> byStatus(SagaStatus status, int limit);

    /** Number of sagas per {@code type} and status, keyed {@code type/STATUS}. */
    Mono<Map<String, Long>> counts();
}
