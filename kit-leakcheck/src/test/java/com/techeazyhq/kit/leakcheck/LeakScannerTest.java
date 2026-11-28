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

package com.techeazyhq.kit.leakcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The check fails when a seeded value leaks, in the form it was written or in a form it is commonly mangled into. */
class LeakScannerTest {

    private final Seeds seeds = new Seeds();
    private final String diagnosis = seeds.unique("diagnosis", "D4");
    private final String name = seeds.shaped("patient name", "D3", "Asha Rao-Menon");
    private final String phone = seeds.shaped("phone number", "D3", "+91 98765 43210");
    private final String hindi = seeds.shaped("note in Hindi", "D4", "रोगी को मधुमेह है");
    private final LeakScanner scanner = new LeakScanner(seeds);

    private List<Leak> scan(String text) {
        return scanner.scan("logs", text);
    }

    @Test
    void aCleanSurfaceHasNoLeaks() {
        assertEquals(List.of(), scan("2026-11-28 INFO created item id=3f2a tenant=9c1b status=OK"));
        scanner.assertClean("logs", "nothing here", "or here", null, "");
    }

    @Test
    void aValueAsWrittenIsFoundAndNamedByLabelNeverByValue() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> scanner.assertClean("application log", "ERROR saving note for " + diagnosis + " failed"));

        assertTrue(failure.getMessage().contains("D4") && failure.getMessage().contains("diagnosis")
                && failure.getMessage().contains("application log"), failure.getMessage());
        assertFalse(failure.getMessage().contains(diagnosis), "the failure must not become the leak");
    }

    @Test
    void theCommonManglingsAreFound() {
        assertFalse(scan(diagnosis.toUpperCase()).isEmpty(), "upper case");
        assertFalse(scan(name.toUpperCase()).isEmpty(), "upper case");
        assertFalse(scan("q=" + URLEncoder.encode(name, StandardCharsets.UTF_8)).isEmpty(), "URL-encoded with plus");
        assertFalse(scan("q=" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")).isEmpty(), "URL-encoded with %20");
        assertFalse(scan("token " + HexFormat.of().formatHex(diagnosis.getBytes(StandardCharsets.UTF_8))).isEmpty(), "hex");
        assertFalse(scan("AsharaoMenon").isEmpty(), "without spaces and dashes");
        assertFalse(scan("call 919876543210 now").isEmpty(), "digits only");
        StringBuilder escaped = new StringBuilder();
        hindi.chars().forEach(c -> escaped.append(c > 126 ? String.format("\\u%04x", c) : String.valueOf((char) c)));
        assertFalse(scan("{\"note\":\"" + escaped + "\"}").isEmpty(), "unicode escapes");
    }

    @Test
    void base64IsFoundAtEveryAlignmentWhateverSurroundsIt() {
        for (String prefix : new String[] {"", "a", "ab", "abc", "user:"}) {
            String encoded = Base64.getEncoder().encodeToString((prefix + diagnosis + ":tail").getBytes(StandardCharsets.UTF_8));

            assertFalse(scan("Authorization: Basic " + encoded).isEmpty(), "prefix '" + prefix + "'");
        }
    }

    @Test
    void whichSeedLeakedAndWhereIsReported() {
        List<Leak> leaks = scanner.scan("event payload", "{\"who\":\"" + name + "\",\"what\":\"" + diagnosis + "\"}");

        assertEquals(2, leaks.size());
        assertEquals("event payload", leaks.get(0).surface());
        assertTrue(leaks.stream().anyMatch(l -> l.label().equals("patient name") && l.classification().equals("D3")));
        assertTrue(leaks.stream().anyMatch(l -> l.label().equals("diagnosis") && l.classification().equals("D4")));
    }

    @Test
    void anOpaqueIdOrAnOrdinaryWordIsNotALeak() {
        assertEquals(List.of(), scan("tenant=11111111-1111-4111-8111-111111111111 patient diagnosis name phone"));
    }

    @Test
    void aSeedMustBeLongEnoughToMeanSomething() {
        assertThrows(IllegalArgumentException.class, () -> new SeededValue("x", "D4", "short"));
        assertThrows(IllegalArgumentException.class, () -> new SeededValue("x", "D4", "      "));
        assertThrows(IllegalArgumentException.class, () -> new SeededValue("x", "D4", null));
    }

    @Test
    void aCheckWithNothingSeededRefusesToPassInsteadOfPassingWithoutChecking() {
        assertThrows(IllegalArgumentException.class, () -> new LeakScanner(new Seeds()));
        assertThrows(IllegalArgumentException.class, () -> new LeakScanner(List.of()));
    }

    @Test
    void theSeedPrintsAsItsLabelOnly() {
        assertFalse(new SeededValue("diagnosis", "D4", "very secret value").toString().contains("secret"));
        assertTrue(Seeds.class.getSimpleName().length() > 0);
    }

    @Test
    void uniqueSeedsDifferEveryTime() {
        Seeds a = new Seeds();
        assertFalse(a.unique("x y", "D4").equals(a.unique("x y", "D4")));
        assertTrue(a.values().get(0).value().startsWith("leakseed-xy-"));
    }
}
