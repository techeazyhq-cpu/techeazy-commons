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

package com.techeazyhq.kit.access;

import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Does one thing an allow obliges (for example {@code audit}: write the audit event; {@code break_glass}: queue the review).
 * It runs after the allow and before the caller proceeds. If it fails, the access does not proceed: an access that could not
 * be audited did not happen.
 */
public interface ObligationHandler {

    /** The key in the decision's {@code obligations} this handler answers to. */
    String key();

    Mono<Void> fulfil(Decision decision, Map<String, Object> input);
}
