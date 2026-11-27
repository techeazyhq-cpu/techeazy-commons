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
 * Where a decision comes from: OPA over HTTP, or a product's own policy engine. The input is the document the policy reads
 * (subject, action, resource, purpose, time, facts); the PEP builds it from the verified token and never from anything the
 * caller can set freely. An error, or no answer, is not a decision: the PEP reads it as unavailable.
 */
public interface PolicyDecisionPoint {

    Mono<Decision> decide(Map<String, Object> input);
}
