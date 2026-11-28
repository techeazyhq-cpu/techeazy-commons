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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The values a test plants. {@link #unique} makes a value that cannot be in the code or the test data by accident: a
 * recognisable prefix and a random tail, so a hit is a leak and never a coincidence.
 */
public final class Seeds {

    private final List<SeededValue> values = new ArrayList<>();

    /** A fresh, unique value for {@code label}, remembered; use the returned text as the input of the flow. */
    public String unique(String label, String classification) {
        String value = "leakseed-" + label.replaceAll("[^A-Za-z0-9]", "").toLowerCase() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        values.add(new SeededValue(label, classification, value));
        return value;
    }

    /** A value of a particular shape (a phone number, an id number) that must also not leak. */
    public String shaped(String label, String classification, String value) {
        values.add(new SeededValue(label, classification, value));
        return value;
    }

    public List<SeededValue> values() {
        return List.copyOf(values);
    }
}
