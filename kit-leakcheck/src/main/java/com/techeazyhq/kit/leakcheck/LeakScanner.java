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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Looks for seeded values in the text a surface produced (logs, events, trace attributes, error bodies). A value is found as
 * written and in the forms it is commonly mangled into on its way out: lower case, URL-encoded, base64 (at any alignment),
 * hex, with spaces and dashes removed, digits only (a phone or id number), and as JSON unicode escapes. It scans text, so it
 * works with whatever a product uses to capture its logs, spans and events.
 *
 * <p>A clean result proves only what was run: the seeds must travel the flows the test exercises, including the failing ones,
 * which is where leaks usually are.
 */
public final class LeakScanner {

    private final List<SeededValue> seeds;
    private final Map<SeededValue, Map<String, String>> forms = new LinkedHashMap<>();

    public LeakScanner(List<SeededValue> seeds) {
        this.seeds = List.copyOf(Objects.requireNonNull(seeds, "seeds"));
        if (this.seeds.isEmpty()) {
            throw new IllegalArgumentException("nothing is seeded: the check would pass without checking anything");
        }
        for (SeededValue seed : this.seeds) {
            forms.put(seed, formsOf(seed.value()));
        }
    }

    public LeakScanner(Seeds seeds) {
        this(seeds.values());
    }

    /** The leaks in the text of one surface. */
    public List<Leak> scan(String surface, Iterable<String> texts) {
        List<Leak> leaks = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (SeededValue seed : seeds) {
                for (Map.Entry<String, String> form : forms.get(seed).entrySet()) {
                    boolean caseBlind = form.getKey().equals("lower case") || form.getKey().equals("as written")
                            || form.getKey().equals("without spaces and dashes");
                    if (caseBlind ? lower.contains(form.getValue().toLowerCase(Locale.ROOT)) : text.contains(form.getValue())) {
                        Leak leak = new Leak(surface, seed.label(), seed.classification(), form.getKey());
                        if (leaks.stream().noneMatch(l -> l.label().equals(leak.label()))) {
                            leaks.add(leak); // one report per value: the first form it was found in
                        }
                        break;
                    }
                }
            }
        }
        return leaks;
    }

    public List<Leak> scan(String surface, String... texts) {
        return scan(surface, java.util.Arrays.asList(texts));
    }

    /**
     * Fails (an {@link AssertionError}) when any text of the surface holds a seeded value. The message names the surface, the
     * label, the class and the form, and never the value.
     */
    public void assertClean(String surface, Iterable<String> texts) {
        List<Leak> leaks = scan(surface, texts);
        if (!leaks.isEmpty()) {
            StringBuilder message = new StringBuilder("Seeded sensitive values leaked:");
            for (Leak leak : leaks) {
                message.append("\n  - ").append(leak.classification()).append(" \"").append(leak.label()).append("\" on ")
                        .append(leak.surface()).append(" (").append(leak.form()).append(")");
            }
            throw new AssertionError(message.toString());
        }
    }

    public void assertClean(String surface, String... texts) {
        assertClean(surface, java.util.Arrays.asList(texts));
    }

    private static Map<String, String> formsOf(String value) {
        Map<String, String> forms = new LinkedHashMap<>();
        forms.put("as written", value);
        forms.put("lower case", value.toLowerCase(Locale.ROOT));
        String url = URLEncoder.encode(value, StandardCharsets.UTF_8);
        if (!url.equals(value)) {
            forms.put("URL-encoded", url);
            forms.put("URL-encoded with %20", url.replace("+", "%20"));
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // base64 of a substring depends on where the value starts in the encoded text: three alignments, each without
        // its first and last group, which are mixed with neighbouring text
        for (int shift = 0; shift < 3; shift++) {
            byte[] padded = new byte[shift + bytes.length];
            System.arraycopy(bytes, 0, padded, shift, bytes.length);
            String encoded = Base64.getEncoder().withoutPadding().encodeToString(padded);
            int from = shift == 0 ? 0 : 4;
            int to = encoded.length() - 4;
            if (to - from >= 8) {
                forms.put("base64 (alignment " + shift + ")", encoded.substring(from, to));
            }
        }
        forms.put("hex", HexFormat.of().formatHex(bytes));
        String squeezed = value.replaceAll("[\\s-]", "");
        if (!squeezed.equals(value) && squeezed.length() >= SeededValue.MIN_LENGTH) {
            forms.put("without spaces and dashes", squeezed);
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() >= 8 && digits.length() * 2 >= value.length()) {
            forms.put("digits only", digits);
        }
        StringBuilder escaped = new StringBuilder();
        boolean any = false;
        for (char c : value.toCharArray()) {
            if (c > 126) {
                escaped.append(String.format("\\u%04x", (int) c));
                any = true;
            } else {
                escaped.append(c);
            }
        }
        if (any) {
            forms.put("JSON unicode escapes", escaped.toString());
        }
        return forms;
    }
}
