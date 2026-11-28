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
 * A read model built from events, in the shape a screen needs (a PostgreSQL table, a MongoDB collection). It only <b>applies</b>
 * events; the {@link ProjectionRunner} sees to order and duplicates. {@code version} is the version of the projection's own
 * logic: when it changes, the runner rebuilds it from the log.
 */
public interface Projection {

    String name();

    int version();

    /** Applies one event, in order for its aggregate and never twice. */
    Mono<Void> apply(LoggedEvent event);

    /** Throws away everything, ready for a replay from the start. */
    Mono<Void> reset();
}
