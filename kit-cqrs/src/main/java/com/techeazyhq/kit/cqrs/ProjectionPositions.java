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

import reactor.core.publisher.Mono;

/**
 * What a projection has applied: the last version of every aggregate (the idempotency and ordering state), the last log
 * sequence (where to continue), and the version of the logic that built it. Kept in the same store as the projection, and
 * updated in the same transaction as the projection itself (see {@link ProjectionRunner}), so the two cannot disagree.
 */
public interface ProjectionPositions {

    /** The last applied version of the aggregate, 0 if none. */
    Mono<Long> appliedVersion(String projection, AggregateKey key);

    /** The last log sequence applied, 0 if none. */
    Mono<Long> lastSequence(String projection);

    /** The version of the projection logic that built what is stored, or 0 if nothing has been built. */
    Mono<Integer> builtWithVersion(String projection);

    Mono<Void> record(String projection, int projectionVersion, AggregateKey key, long version, long sequence);

    /** Forgets everything about the projection (for a rebuild). */
    Mono<Void> reset(String projection);
}
