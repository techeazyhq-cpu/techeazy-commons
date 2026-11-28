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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** A projection is the same however it was built, and never silently wrong. */
class ProjectionRunnerTest {

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryProjectionPositions positions = new InMemoryProjectionPositions();
    private final Fixtures.NamesProjection projection = new Fixtures.NamesProjection();
    private final ProjectionRunner runner = new ProjectionRunner(projection, log, positions);

    private void append(String id, long version, String type, String name) {
        log.append(event(id, version, type, name)).block();
    }

    @Test
    void theProjectionFollowsTheLog() {
        append("a", 1, "ItemNamed", "Stethoscope");
        append("b", 1, "ItemNamed", "Gloves");
        append("a", 2, "ItemNamed", "Stethoscope Pro");

        assertEquals(3L, runner.catchUp().block());

        assertEquals(Map.of("a", "Stethoscope Pro", "b", "Gloves"), projection.names);
        assertEquals(0L, runner.lag().block());
    }

    @Test
    void whatIsAlreadyAppliedIsNotAppliedAgain() {
        append("a", 1, "ItemNamed", "Stethoscope");
        runner.catchUp().block();

        assertEquals(0L, runner.catchUp().block());
        assertEquals(1, projection.applied);
    }

    @Test
    void anEventDeliveredTwiceChangesNothing() {
        append("a", 1, "ItemNamed", "Stethoscope");
        runner.catchUp().block();
        // a redelivery: the same position replayed from an earlier sequence
        positions.reset("names").block();
        positions.record("names", 1, log.read(0, 1).blockFirst().event().aggregate(), 1, 0).block();

        runner.catchUp().block();

        assertEquals(Map.of("a", "Stethoscope"), projection.names);
        assertEquals(1, projection.applied, "the duplicate was skipped, not applied");
    }

    @Test
    void aMissingEventStopsTheRunInsteadOfBuildingAWrongProjection() {
        EventRecord second = event("a", 2, "ItemNamed", "Stethoscope Pro");
        // the log holds version 2 without version 1: as if an event were lost on the way
        EventLog broken = new EventLog() {
            @Override
            public reactor.core.publisher.Mono<Void> append(EventRecord e) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Flux<LoggedEvent> read(long after, int limit) {
                return after == 0 ? reactor.core.publisher.Flux.just(new LoggedEvent(1, second)) : reactor.core.publisher.Flux.empty();
            }

            @Override
            public reactor.core.publisher.Mono<Long> head() {
                return reactor.core.publisher.Mono.just(1L);
            }
        };
        ProjectionRunner stuck = new ProjectionRunner(projection, broken, positions);

        StepVerifier.create(stuck.catchUp()).expectError(OutOfOrderException.class).verify();
        assertEquals(Map.of(), projection.names);
    }

    @Test
    void rebuildingFromTheLogGivesExactlyWhatWasThereBefore() {
        append("a", 1, "ItemNamed", "Stethoscope");
        append("b", 1, "ItemNamed", "Gloves");
        append("a", 2, "ItemNamed", "Stethoscope Pro");
        append("b", 2, "ItemRemoved", "");
        runner.catchUp().block();
        Map<String, String> before = new HashMap<>(projection.names);

        assertEquals(4L, runner.rebuild().block());

        assertEquals(before, projection.names);
        assertEquals(Map.of("a", "Stethoscope Pro"), projection.names);
    }

    @Test
    void aProjectionRebuiltFromEventsEqualsTheSourceAfterManyRandomChanges() {
        // the source of truth: the state each command left the aggregates in; the events are what those commands recorded
        Random random = new Random(7);
        Map<String, String> source = new HashMap<>();
        Map<String, Long> versions = new HashMap<>();
        for (int i = 0; i < 2_000; i++) {
            String id = "item-" + random.nextInt(60);
            long next = versions.merge(id, 1L, Long::sum);
            if (source.containsKey(id) && random.nextInt(5) == 0) {
                source.remove(id);
                append(id, next, "ItemRemoved", "");
            } else {
                String name = "name-" + random.nextInt(1_000_000);
                source.put(id, name);
                append(id, next, "ItemNamed", name);
            }
        }

        runner.catchUp().block();
        assertTrue(Reconciliation.compare(source, projection.names).isEmpty(), "live projection equals the source");

        runner.rebuild().block();
        Reconciliation.Diff diff = Reconciliation.compare(source, projection.names);
        assertTrue(diff.isEmpty(), "rebuilt projection equals the source: " + diff);
        assertEquals(source, projection.names);
    }

    @Test
    void newerProjectionLogicRebuildsItselfFromTheLog() {
        append("a", 1, "ItemNamed", "Stethoscope");
        runner.catchUp().block();
        projection.version = 2;
        projection.names.put("stale", "left by the old logic");

        assertEquals(1L, runner.catchUp().block());

        assertEquals(Map.of("a", "Stethoscope"), projection.names);
    }

    @Test
    void lagCountsTheEventsNotYetApplied() {
        append("a", 1, "ItemNamed", "x");
        append("a", 2, "ItemNamed", "y");

        assertEquals(2L, runner.lag().block());
        runner.catchUp().block();
        assertEquals(0L, runner.lag().block());
    }

    @Test
    void theRunnerPagesThroughALogLongerThanOneBatch() {
        for (long v = 1; v <= 25; v++) {
            append("a", v, "ItemNamed", "n" + v);
        }
        ProjectionRunner small = new ProjectionRunner(projection, log, positions, java.util.function.UnaryOperator.identity(), 4);

        assertEquals(25L, small.catchUp().block());
        assertEquals("n25", projection.names.get("a"));
    }

    @Test
    void theApplyAndTheRecordRunInsideTheTransactionTheCallerSupplies() {
        append("a", 1, "ItemNamed", "x");
        int[] wrapped = {0};
        ProjectionRunner transactional = new ProjectionRunner(projection, log, positions, work -> {
            wrapped[0]++;
            return work;
        }, 10);

        transactional.catchUp().block();

        assertEquals(1, wrapped[0]);
    }
}
