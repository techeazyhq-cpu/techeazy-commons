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

import java.util.Objects;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;

/**
 * Keeps a {@link Projection} up to date from the {@link EventLog}, and rebuilds it.
 *
 * <ul>
 *   <li><b>Idempotent.</b> An event whose version is not above what the aggregate has already applied is skipped, so redelivery
 *       and replays change nothing.</li>
 *   <li><b>Ordered per aggregate.</b> The next event must be exactly one version up; anything else is an
 *       {@link OutOfOrderException} and the run stops (never skipped over).</li>
 *   <li><b>Atomic.</b> Applying an event and recording the position happen inside {@code atomically} (a database transaction
 *       in the PostgreSQL adapters), so a crash between them cannot leave the two disagreeing.</li>
 *   <li><b>Rebuildable.</b> {@link #rebuild} resets the projection and replays the whole log. {@link #catchUp} rebuilds by
 *       itself when the projection's logic version changed.</li>
 * </ul>
 */
public final class ProjectionRunner {

    private final Projection projection;
    private final EventLog log;
    private final ProjectionPositions positions;
    private final UnaryOperator<Mono<Void>> atomically;
    private final int batch;

    /** {@code atomically} wraps one event's apply and record in a transaction; use the identity for in-memory stores. */
    public ProjectionRunner(Projection projection, EventLog log, ProjectionPositions positions,
                            UnaryOperator<Mono<Void>> atomically, int batch) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.log = Objects.requireNonNull(log, "log");
        this.positions = Objects.requireNonNull(positions, "positions");
        this.atomically = Objects.requireNonNull(atomically, "atomically");
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be at least 1");
        }
        this.batch = batch;
    }

    public ProjectionRunner(Projection projection, EventLog log, ProjectionPositions positions) {
        this(projection, log, positions, UnaryOperator.identity(), 500);
    }

    /** Applies everything new. Rebuilds first when the projection's logic is newer than what was built. Events applied. */
    public Mono<Long> catchUp() {
        return positions.builtWithVersion(projection.name()).flatMap(built -> {
            if (built != 0 && built != projection.version()) {
                return rebuild();
            }
            return drain();
        });
    }

    /** Throws the projection away and replays the whole log. Events applied. */
    public Mono<Long> rebuild() {
        return projection.reset().then(positions.reset(projection.name())).then(Mono.defer(this::drain));
    }

    /** Events in the log the projection has not applied yet: the projection lag. */
    public Mono<Long> lag() {
        return Mono.zip(log.head(), positions.lastSequence(projection.name()), (head, applied) -> Math.max(0, head - applied));
    }

    private Mono<Long> drain() {
        return positions.lastSequence(projection.name()).flatMap(from -> log.read(from, batch)
                .concatMap(this::applyOne).reduce(0L, Long::sum)
                .flatMap(applied -> applied == 0 ? Mono.just(0L) : Mono.defer(this::drain).map(more -> applied + more)));
    }

    /** 1 when the event was applied, 0 when it was a duplicate. */
    private Mono<Long> applyOne(LoggedEvent logged) {
        EventRecord event = logged.event();
        AggregateKey key = event.aggregate();
        return positions.appliedVersion(projection.name(), key).flatMap(applied -> {
            if (event.version() <= applied) {
                return positions.lastSequence(projection.name()).flatMap(seq -> logged.sequence() > seq
                        ? positions.record(projection.name(), projection.version(), key, applied, logged.sequence()).thenReturn(0L)
                        : Mono.just(0L));
            }
            if (event.version() != applied + 1) {
                return Mono.error(new OutOfOrderException(key, applied + 1, event.version()));
            }
            return atomically.apply(projection.apply(logged)
                            .then(positions.record(projection.name(), projection.version(), key, event.version(), logged.sequence())))
                    .thenReturn(1L);
        });
    }
}
