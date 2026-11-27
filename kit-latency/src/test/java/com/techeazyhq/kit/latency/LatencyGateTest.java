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

package com.techeazyhq.kit.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatencyGateTest {

    private static final long MS = 1_000_000L;

    private static Samples of(int n, long ms) {
        Samples s = new Samples();
        for (int i = 0; i < n; i++) {
            s.record(ms * MS);
        }
        return s;
    }

    /** 95 fast samples and 5 slow ones: the p95 is the last fast one, the p99 a slow one. */
    private static Samples mix(long fastMs, long slowMs) {
        Samples s = of(95, fastMs);
        for (int i = 0; i < 5; i++) {
            s.record(slowMs * MS);
        }
        return s;
    }

    // ---- percentiles -----------------------------------------------------------------------------------------------------

    @Test
    void thePercentileIsNearestRankAndNeverInventsAValue() {
        Samples s = new Samples();
        for (int ms = 1; ms <= 100; ms++) {
            s.record(ms * MS);
        }

        assertEquals(50.0, s.percentileMs(50));
        assertEquals(95.0, s.percentileMs(95));
        assertEquals(99.0, s.percentileMs(99));
        assertEquals(100.0, s.percentileMs(100));
        assertEquals(1.0, s.percentileMs(0.5), "the smallest rank is the first sample");
        assertEquals(100.0, s.maxMs());
    }

    @Test
    void aSingleOutlierDoesNotMoveP95ButDoesMoveTheMax() {
        Samples s = of(99, 10);
        s.record(5_000 * MS);

        assertEquals(10.0, s.percentileMs(95));
        assertEquals(10.0, s.percentileMs(99));
        assertEquals(5_000.0, s.maxMs());
    }

    @Test
    void percentilesNeedSamplesAndAValidRank() {
        assertThrows(IllegalStateException.class, () -> new Samples().percentileMs(95));
        assertThrows(IllegalArgumentException.class, () -> of(5, 1).percentileMs(0));
        assertThrows(IllegalArgumentException.class, () -> of(5, 1).percentileMs(101));
        assertThrows(IllegalArgumentException.class, () -> new Samples().record(-1));
    }

    @Test
    void samplesCanBeRecordedFromManyThreadsWithoutLoss() throws Exception {
        Samples s = new Samples();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 1_000; i++) {
                    s.record(MS);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(8_000, s.count());
    }

    // ---- the SLOs --------------------------------------------------------------------------------------------------------

    @Test
    void theSlosAreTheOnesInTheStandard() {
        assertEquals(300, Slo.READ.p95TargetMs());
        assertEquals(1000, Slo.READ.p99TargetMs());
        assertEquals(600, Slo.WRITE.p95TargetMs());
        assertEquals(2000, Slo.WRITE.p99TargetMs());
        assertEquals(210.0, Slo.READ.gateP95Ms(), 0.0001, "70% of 300");
        assertEquals(420.0, Slo.WRITE.gateP95Ms(), 0.0001, "70% of 600");
        assertThrows(IllegalArgumentException.class, () -> new Slo("x", 100, 50));
    }

    // ---- the gate: it must fail when a service is slow --------------------------------------------------------------------

    @Test
    void aFastServicePasses() {
        LatencyGate.Verdict v = LatencyGate.evaluate(new Measurement("GET /items/{id}", Slo.READ, of(200, 8)));

        assertTrue(v.passed(), v.reasons().toString());
        assertEquals(8.0, v.p95Ms());
    }

    @Test
    void aServiceWhoseP95IsOverTheGateFails() {
        // 250 ms is under the 300 ms target but over the 210 ms gate: the 70% rule leaves room for the gateway and the tail
        LatencyGate.Verdict v = LatencyGate.evaluate(new Measurement("GET /items/{id}", Slo.READ, of(200, 250)));

        assertFalse(v.passed());
        assertTrue(v.reasons().get(0).contains("p95") && v.reasons().get(0).contains("70%"), v.reasons().toString());
    }

    @Test
    void theGateIsExactlyAtTheLine() {
        assertTrue(LatencyGate.evaluate(new Measurement("r", Slo.READ, of(200, 210))).passed());
        assertFalse(LatencyGate.evaluate(new Measurement("r", Slo.READ, of(200, 211))).passed());
        assertTrue(LatencyGate.evaluate(new Measurement("w", Slo.WRITE, of(200, 420))).passed());
        assertFalse(LatencyGate.evaluate(new Measurement("w", Slo.WRITE, of(200, 421))).passed());
    }

    @Test
    void aSlowTailFailsEvenWithAGoodP95() {
        // p95 is 10 ms, p99 is 1.5 s: users in the tail wait too long
        LatencyGate.Verdict v = LatencyGate.evaluate(new Measurement("r", Slo.READ, mix(10, 1_500)));

        assertEquals(10.0, v.p95Ms());
        assertFalse(v.passed());
        assertTrue(v.reasons().get(0).contains("p99"));
    }

    @Test
    void aFailedRequestFailsTheRunNoMatterHowFastTheOthersWere() {
        Samples s = of(200, 5);
        s.recordFailure();

        LatencyGate.Verdict v = LatencyGate.evaluate(new Measurement("r", Slo.READ, s));

        assertFalse(v.passed());
        assertTrue(v.reasons().get(0).contains("failed"));
    }

    @Test
    void tooFewSamplesIsNotAPassAndNothingIsNotAPassEither() {
        assertFalse(LatencyGate.evaluate(new Measurement("r", Slo.READ, of(99, 5))).passed());
        assertTrue(LatencyGate.evaluate(new Measurement("r", Slo.READ, of(100, 5))).passed());
        LatencyGate.Verdict none = LatencyGate.evaluate(new Measurement("r", Slo.READ, new Samples()));
        assertFalse(none.passed());
        assertEquals(0, none.count());
    }

    // ---- steps -----------------------------------------------------------------------------------------------------------

    @Test
    void aStepSlightlyOverItsBudgetIsReportedButDoesNotFailTheRun() {
        Measurement m = new Measurement("r", Slo.READ, of(200, 50)).step("database", of(200, 60), 40);

        LatencyGate.Verdict v = LatencyGate.evaluate(m);

        assertTrue(v.passed(), v.reasons().toString());
        assertTrue(v.steps().get(0).overBudget());
        assertFalse(v.steps().get(0).failed());
    }

    @Test
    void aStepMoreThanThreeTimesItsBudgetFailsTheRun() {
        Measurement m = new Measurement("r", Slo.READ, of(200, 50)).step("database", of(200, 121), 40);

        LatencyGate.Verdict v = LatencyGate.evaluate(m);

        assertFalse(v.passed());
        assertTrue(v.reasons().get(0).contains("database") && v.reasons().get(0).contains("3 times"));
        assertTrue(LatencyGate.evaluate(new Measurement("r", Slo.READ, of(200, 50)).step("database", of(200, 120), 40)).passed());
    }

    @Test
    void aStepWithinItsBudgetIsWithin() {
        LatencyGate.Verdict v = LatencyGate.evaluate(new Measurement("r", Slo.READ, of(200, 20)).step("database", of(200, 5), 40));

        assertFalse(v.steps().get(0).overBudget());
    }

    // ---- the report ------------------------------------------------------------------------------------------------------

    @Test
    void theReportShowsEveryRouteItsPercentilesTheGateAndEachStepAgainstItsBudget() {
        List<LatencyGate.Verdict> verdicts = List.of(
                LatencyGate.evaluate(new Measurement("GET /items/{id}", Slo.READ, of(200, 8)).step("database", of(200, 3), 40)),
                LatencyGate.evaluate(new Measurement("POST /items", Slo.WRITE, of(200, 450))));

        String md = LatencyReport.markdown("Latency of a service", verdicts);

        assertTrue(md.startsWith("# Latency of a service"));
        assertTrue(md.contains("| `GET /items/{id}` | read | 200 | 8.0 | 8.0 | 8.0 | 8.0 | 210 | 1000 | pass |"), md);
        assertTrue(md.contains("**FAIL**"));
        assertTrue(md.contains("### Steps of `GET /items/{id}`"));
        assertTrue(md.contains("| database | 3.0 | 40 | within |"), md);
        assertTrue(md.contains("**POST /items:** p95 450.0 ms is over the gate of 420.0 ms"), md);
    }

    @Test
    void theJsonReportCanBeTrendedAndEscapesTheRoute(@TempDir Path dir) throws Exception {
        List<LatencyGate.Verdict> verdicts = List.of(LatencyGate.evaluate(new Measurement("GET \"/x\"", Slo.READ, of(200, 8))));

        LatencyReport.write(dir.resolve("nested/reports"), "t", verdicts);

        String json = Files.readString(dir.resolve("nested/reports/latency-report.json"));
        assertTrue(json.startsWith("[{\"route\":\"GET \\\"/x\\\"\""), json);
        assertTrue(json.contains("\"passed\":true"));
        assertTrue(Files.readString(dir.resolve("nested/reports/latency-report.md")).startsWith("# t"));
    }
}
