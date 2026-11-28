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

import static com.techeazyhq.kit.cqrs.Fixtures.event;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OutboxRelayTest {

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final List<String> published = new ArrayList<>();
    private boolean failOn;
    private String failingId;

    private final EventPublisher publisher = e -> {
        if (failOn && e.aggregateId().equals(failingId)) {
            return Mono.error(new IllegalStateException("broker down"));
        }
        published.add(e.aggregateId() + "@" + e.version());
        return Mono.empty();
    };

    private OutboxRelay relay(int batch) {
        return new OutboxRelay("r1", log, log, publisher, batch);
    }

    private void append(String id, long version) {
        log.append(event(id, version, "ItemNamed", "{}")).block();
    }

    @Test
    void everyEventIsPublishedOnceInLogOrderAndTheCursorMoves() {
        append("a", 1);
        append("b", 1);
        append("a", 2);

        StepVerifier.create(relay(10).runOnce()).expectNext(3).verifyComplete();

        assertEquals(List.of("a@1", "b@1", "a@2"), published);
        assertEquals(3L, log.get("r1").block());
        StepVerifier.create(relay(10).runOnce()).expectNext(0).verifyComplete();
        assertEquals(3, published.size(), "nothing is published twice");
    }

    @Test
    void aBatchIsPublishedPerRunUntilTheLogIsDrained() {
        for (long v = 1; v <= 5; v++) {
            append("a", v);
        }
        OutboxRelay relay = relay(2);

        assertEquals(2, relay.runOnce().block());
        assertEquals(3L, relay.lag().block());
        assertEquals(2, relay.runOnce().block());
        assertEquals(1, relay.runOnce().block());
        assertEquals(0L, relay.lag().block());
    }

    @Test
    void aFailureStopsTheRunKeepsWhatWasPublishedAndTheNextRunContinuesFromTheFailedEvent() {
        append("a", 1);
        append("b", 1);
        append("c", 1);
        failOn = true;
        failingId = "b";

        StepVerifier.create(relay(10).runOnce()).expectError(IllegalStateException.class).verify();

        assertEquals(List.of("a@1"), published, "c is not published past the failed b: order is kept");
        assertEquals(1L, log.get("r1").block());

        failOn = false;
        assertEquals(2, relay(10).runOnce().block());
        assertEquals(List.of("a@1", "b@1", "c@1"), published);
    }

    @Test
    void twoRelaysWithTheSameNameCannotBothMoveTheCursor() {
        append("a", 1);

        assertEquals(true, log.advance("r1", 0, 1).block());
        assertEquals(false, log.advance("r1", 0, 1).block());
    }

    @Test
    void aVersionThatIsNotTheNextOneIsAConflict() {
        append("a", 1);

        StepVerifier.create(log.append(event("a", 1, "ItemNamed", "{}"))).expectError(VersionConflictException.class).verify();
        StepVerifier.create(log.append(event("a", 3, "ItemNamed", "{}"))).expectError(VersionConflictException.class).verify();
        assertEquals(1L, log.head().block());
    }
}
