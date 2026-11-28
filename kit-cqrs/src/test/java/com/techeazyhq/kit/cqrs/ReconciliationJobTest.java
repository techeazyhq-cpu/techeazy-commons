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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ReconciliationJobTest {

    private static final class Recorder implements ReconciliationJob.Alerts {
        final List<String> diverged = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<Long> skipped = new ArrayList<>();
        int completed;
        Reconciliation.Diff lastDiff;

        @Override
        public void diverged(String projection, String tenant, Reconciliation.Diff diff) {
            diverged.add(tenant);
            lastDiff = diff;
        }

        @Override
        public void failed(String projection, String tenant, Throwable failure) {
            failed.add(tenant);
        }

        @Override
        public void skipped(String projection, long lag) {
            skipped.add(lag);
        }

        @Override
        public void completed(String projection, int tenantsCompared, int tenantsDiverged) {
            completed++;
        }
    }

    private final Map<String, Map<String, String>> source = new HashMap<>();
    private final Map<String, Map<String, String>> view = new HashMap<>();
    private final AtomicLong lag = new AtomicLong();
    private final Recorder alerts = new Recorder();

    private ReconciliationJob job() {
        return new ReconciliationJob("items", () -> Flux.fromIterable(Set.copyOf(java.util.stream.Stream.concat(source.keySet().stream(),
                view.keySet().stream()).toList())), t -> Mono.just(source.getOrDefault(t, Map.of())),
                t -> Mono.just(view.getOrDefault(t, Map.of())), () -> Mono.just(lag.get()), alerts);
    }

    @Test
    void aProjectionThatEqualsItsSourceRaisesNothing() {
        source.put("t1", Map.of("a", "1", "b", "2"));
        view.put("t1", Map.of("a", "1", "b", "2"));

        List<ReconciliationJob.Result> results = job().runOnce().block();

        assertEquals(1, results.size());
        assertTrue(alerts.diverged.isEmpty());
        assertEquals(1, alerts.completed);
    }

    @Test
    void aDeliberatelyAlteredViewIsReportedWithTheKeysThatDifferAndNeverTheValues() {
        source.put("t1", Map.of("a", "1", "b", "2", "c", "3"));
        view.put("t1", Map.of("a", "1", "b", "TAMPERED-SECRET", "x", "9"));

        job().runOnce().block();

        assertEquals(List.of("t1"), alerts.diverged);
        assertEquals(Set.of("c"), alerts.lastDiff.missing());
        assertEquals(Set.of("x"), alerts.lastDiff.unexpected());
        assertEquals(Set.of("b"), alerts.lastDiff.changed());
        assertFalse(alerts.lastDiff.toString().contains("TAMPERED-SECRET"), "names keys, not values");
    }

    @Test
    void aTenantThatExistsOnlyInTheViewIsReported() {
        view.put("ghost", Map.of("k", "v"));

        job().runOnce().block();

        assertEquals(List.of("ghost"), alerts.diverged);
        assertEquals(Set.of("k"), alerts.lastDiff.unexpected());
    }

    @Test
    void nothingIsComparedWhileTheProjectionIsStillCatchingUp() {
        source.put("t1", Map.of("a", "1"));
        lag.set(7);

        List<ReconciliationJob.Result> results = job().runOnce().block();

        assertTrue(results.isEmpty());
        assertEquals(List.of(7L), alerts.skipped);
        assertTrue(alerts.diverged.isEmpty(), "being behind is not a divergence");
    }

    @Test
    void aDifferenceThatHasGoneByTheSecondLookIsNotAnAlert() {
        source.put("t1", Map.of("a", "1"));
        int[] reads = {0};
        ReconciliationJob job = new ReconciliationJob("items", () -> Flux.just("t1"), t -> Mono.just(source.get(t)),
                t -> Mono.fromSupplier(() -> reads[0]++ == 0 ? Map.<String, String>of() : Map.of("a", "1")), () -> Mono.just(0L), alerts);

        job.runOnce().block();

        assertTrue(alerts.diverged.isEmpty(), "the write that landed between the two reads is not a divergence");
    }

    @Test
    void aTenantThatCannotBeComparedIsReportedAndTheOthersStillAre() {
        source.put("t1", Map.of("a", "1"));
        source.put("t2", Map.of("a", "1"));
        view.put("t2", Map.of("a", "2"));
        ReconciliationJob job = new ReconciliationJob("items", () -> Flux.just("t1", "t2"), t -> Mono.just(source.get(t)),
                t -> t.equals("t1") ? Mono.error(new IllegalStateException("store down")) : Mono.just(view.get(t)), () -> Mono.just(0L), alerts);

        List<ReconciliationJob.Result> results = job.runOnce().block();

        assertEquals(List.of("t1"), alerts.failed);
        assertEquals(List.of("t2"), alerts.diverged);
        assertTrue(results.get(0).failed());
    }

    @Test
    void theScheduleKeepsRunningAfterARunFailsAndStopsWhenDisposed() throws Exception {
        source.put("t1", Map.of("a", "1"));
        view.put("t1", Map.of("a", "1"));
        int[] runs = {0};
        ReconciliationJob job = new ReconciliationJob("items", () -> Flux.just("t1"), t -> Mono.just(source.get(t)),
                t -> Mono.just(view.get(t)), () -> ++runs[0] == 1 ? Mono.error(new IllegalStateException("lag unreadable")) : Mono.just(0L),
                alerts);

        Disposable running = job.schedule(Duration.ofMillis(20));
        Thread.sleep(300);
        running.dispose();
        int after = alerts.completed;
        Thread.sleep(100);

        assertTrue(alerts.failed.contains("*"), "the failed run was reported");
        assertTrue(after >= 2, "and later runs happened: " + after);
        assertEquals(after, alerts.completed, "none after dispose");
    }
}
