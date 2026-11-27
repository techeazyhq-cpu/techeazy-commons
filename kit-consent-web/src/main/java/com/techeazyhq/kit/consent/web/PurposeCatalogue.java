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

package com.techeazyhq.kit.consent.web;

import com.techeazyhq.kit.consent.PurposeCode;

/**
 * A product's closed list of purposes. The purpose-of-use filter accepts only codes this says it knows, so a request cannot
 * name a purpose the product has not defined. A product usually backs it with an enum:
 * {@code code -> Purpose.find(code).isPresent()}.
 */
@FunctionalInterface
public interface PurposeCatalogue {

    /** Whether the product has this purpose. */
    boolean contains(PurposeCode code);
}
