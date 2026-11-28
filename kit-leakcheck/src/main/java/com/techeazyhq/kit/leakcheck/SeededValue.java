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

import java.util.Objects;

/**
 * One value planted in a test so that its appearance somewhere it must not be can be seen. {@code label} names it for the
 * failure message ({@code patient name}); the value itself is never printed in a failure, so the build log does not become
 * the leak. {@code classification} is the product's own class for it (for example {@code D4}).
 */
public record SeededValue(String label, String classification, String value) {

    /** Shorter values match too much text by chance to mean anything. */
    public static final int MIN_LENGTH = 6;

    public SeededValue {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(classification, "classification");
        if (value == null || value.strip().length() < MIN_LENGTH) {
            throw new IllegalArgumentException("a seeded value needs at least " + MIN_LENGTH
                    + " characters, or it matches text by chance: " + label);
        }
    }

    /** Never the value. */
    @Override
    public String toString() {
        return "SeededValue[" + label + ", " + classification + "]";
    }
}
