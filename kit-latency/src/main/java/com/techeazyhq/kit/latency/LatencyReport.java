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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** The report a run leaves behind: a table to read and a JSON file for anything that wants to trend it. */
public final class LatencyReport {

    private LatencyReport() {
    }

    public static String markdown(String title, List<LatencyGate.Verdict> verdicts) {
        StringBuilder out = new StringBuilder("# ").append(title).append("\n\n");
        out.append("Budget: the product's NFR baseline. The gate is the p95 target times 70%, p99 at its target, and no step more than "
                + "3 times its own budget. A request that failed fails the run.\n\n");
        out.append("| Route | Kind | n | p50 ms | p95 ms | p99 ms | max ms | Gate p95 ms | Gate p99 ms | Result |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (LatencyGate.Verdict v : verdicts) {
            out.append(String.format(Locale.ROOT, "| `%s` | %s | %d | %.1f | %.1f | %.1f | %.1f | %.0f | %.0f | %s |%n",
                    v.route(), v.kind(), v.count(), v.p50Ms(), v.p95Ms(), v.p99Ms(), v.maxMs(), v.gateP95Ms(), v.gateP99Ms(),
                    v.passed() ? "pass" : "**FAIL**"));
        }
        for (LatencyGate.Verdict v : verdicts) {
            if (!v.steps().isEmpty()) {
                out.append("\n### Steps of `").append(v.route()).append("`\n\n| Step | p95 ms | Budget ms | |\n|---|---:|---:|---|\n");
                for (LatencyGate.StepResult s : v.steps()) {
                    out.append(String.format(Locale.ROOT, "| %s | %.1f | %.0f | %s |%n", s.name(), s.p95Ms(), s.budgetP95Ms(),
                            s.failed() ? "**FAIL**" : s.overBudget() ? "over budget" : "within"));
                }
            }
            for (String reason : v.reasons()) {
                out.append("\n- **").append(v.route()).append(":** ").append(reason);
            }
        }
        return out.append('\n').toString();
    }

    public static String json(List<LatencyGate.Verdict> verdicts) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < verdicts.size(); i++) {
            LatencyGate.Verdict v = verdicts.get(i);
            out.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"route\":\"%s\",\"kind\":\"%s\",\"count\":%d,\"failures\":%d,\"p50Ms\":%.3f,\"p95Ms\":%.3f,\"p99Ms\":%.3f,"
                            + "\"maxMs\":%.3f,\"gateP95Ms\":%.1f,\"gateP99Ms\":%.1f,\"passed\":%b}",
                    escape(v.route()), v.kind(), v.count(), v.failures(), v.p50Ms(), v.p95Ms(), v.p99Ms(), v.maxMs(),
                    v.gateP95Ms(), v.gateP99Ms(), v.passed()));
        }
        return out.append("]\n").toString();
    }

    /** Writes {@code latency-report.md} and {@code latency-report.json} into the directory. */
    public static void write(Path directory, String title, List<LatencyGate.Verdict> verdicts) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("latency-report.md"), markdown(title, verdicts), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("latency-report.json"), json(verdicts), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
