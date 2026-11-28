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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The ordered log of events: the outbox the relay publishes from and the history projections are rebuilt from. The write side
 * calls {@link #append} <b>inside the transaction that changes the aggregate</b>, so the change and its event commit together or
 * not at all (never a change without its event, never an event without its change, never a dual write).
 *
 * <p>Events are kept, not deleted, once published: the rebuild needs them. Archiving old events is a retention decision of the
 * product, made with the projections' rebuild needs in mind.
 */
public interface EventLog {

    /**
     * Adds the event. A second event with the same aggregate and version is a {@link VersionConflictException}; so is a version
     * that is not the next one of the aggregate.
     */
    Mono<Void> append(EventRecord event);

    /** Events with a sequence greater than {@code after}, ascending, at most {@code limit}, and only ones that are final. */
    Flux<LoggedEvent> read(long after, int limit);

    /** The sequence of the last final event, or 0 when the log is empty. */
    Mono<Long> head();
}
