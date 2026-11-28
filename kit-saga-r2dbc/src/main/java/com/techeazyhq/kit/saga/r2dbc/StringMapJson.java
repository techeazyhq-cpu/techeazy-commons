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

package com.techeazyhq.kit.saga.r2dbc;

import java.util.LinkedHashMap;
import java.util.Map;

/** A JSON object of strings to strings and nothing else: enough for saga data, with no library to depend on. */
final class StringMapJson {

    private StringMapJson() {
    }

    static String write(Map<String, String> map) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            quote(out, entry.getKey());
            out.append(':');
            quote(out, entry.getValue());
        }
        return out.append('}').toString();
    }

    static Map<String, String> read(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        int[] at = {0};
        skip(json, at);
        expect(json, at, '{');
        skip(json, at);
        if (json.charAt(at[0]) == '}') {
            return map;
        }
        while (true) {
            skip(json, at);
            String key = string(json, at);
            skip(json, at);
            expect(json, at, ':');
            skip(json, at);
            map.put(key, string(json, at));
            skip(json, at);
            char next = json.charAt(at[0]++);
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw new IllegalArgumentException("bad saga data");
            }
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String string(String json, int[] at) {
        expect(json, at, '"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = json.charAt(at[0]++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = json.charAt(at[0]++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(json.substring(at[0], at[0] + 4), 16));
                    at[0] += 4;
                }
                default -> throw new IllegalArgumentException("bad saga data");
            }
        }
    }

    private static void skip(String json, int[] at) {
        while (at[0] < json.length() && Character.isWhitespace(json.charAt(at[0]))) {
            at[0]++;
        }
    }

    private static void expect(String json, int[] at, char c) {
        if (at[0] >= json.length() || json.charAt(at[0]++) != c) {
            throw new IllegalArgumentException("bad saga data");
        }
    }
}
