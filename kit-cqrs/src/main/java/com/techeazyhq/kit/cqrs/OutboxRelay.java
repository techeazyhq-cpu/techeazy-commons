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

import java.time.Duration;
import java.util.Objects;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes the log in order. Each run reads the events after the cursor, publishes them one at a time in sequence order (so
 * the events of one aggregate always leave in version order), and moves the cursor to the last one published. The first failure
 * stops the run: nothing after it is published, the cursor stays, and the next run starts again from there. Nothing is skipped
 * and nothing is published out of order; delivery is at least once.
 */
public final class OutboxRelay {

    private final String name;
    private final EventLog log;
    private final PublishCursor cursor;
    private final EventPublisher publisher;
    private final int batch;

    public OutboxRelay(String name, EventLog log, PublishCursor cursor, EventPublisher publisher, int batch) {
        this.name = Objects.requireNonNull(name, "name");
        this.log = Objects.requireNonNull(log, "log");
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be at least 1");
        }
        this.batch = batch;
    }

    /** One run. The number of events published; an error if one could not be (events before it are published and recorded). */
    public Mono<Integer> runOnce() {
        return cursor.get(name).flatMap(from -> {
            long[] last = {from};
            return log.read(from, batch)
                    .concatMap(logged -> publisher.publish(logged.event()).thenReturn(logged.sequence()))
                    .doOnNext(sequence -> last[0] = sequence)
                    .count()
                    .onErrorResume(failure -> record(from, last[0]).then(Mono.<Long>error(failure)))
                    .flatMap(count -> record(from, last[0]).thenReturn(count.intValue()));
        });
    }

    private Mono<Boolean> record(long from, long to) {
        return to > from ? cursor.advance(name, from, to) : Mono.just(true);
    }

    /** Runs every {@code interval} until disposed. A failed run is logged and tried again on the next tick. */
    public Disposable schedule(Duration interval) {
        return Flux.interval(interval, Schedulers.parallel())
                .onBackpressureDrop()
                .concatMap(tick -> runOnce().onErrorResume(failure -> {
                    System.getLogger(OutboxRelay.class.getName()).log(System.Logger.Level.WARNING,
                            "outbox relay {0} will retry: {1}", name, failure.getClass().getSimpleName());
                    return Mono.empty();
                }))
                .subscribe();
    }

    /** How many events are waiting to be published: the outbox lag. */
    public Mono<Long> lag() {
        return Mono.zip(log.head(), cursor.get(name), (head, published) -> Math.max(0, head - published));
    }
}
