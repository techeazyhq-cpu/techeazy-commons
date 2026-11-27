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

package com.techeazyhq.kit.consent;

import java.util.regex.Pattern;

/**
 * Why data is processed, as a code ({@code CARE_DELIVERY}), never as text. A product's catalogue of codes is its own;
 * the port only insists on the shape, so a purpose cannot carry free text.
 */
public record PurposeCode(String value) {

    private static final Pattern SHAPE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    public PurposeCode {
        if (value == null || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("A purpose code is upper-case letters, digits and underscores, 3 to 64");
        }
    }

    public static PurposeCode of(String value) {
        return new PurposeCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
