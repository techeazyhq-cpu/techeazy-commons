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

// The Policy Enforcement Point: the one place a service asks "may this person do this?" of its Policy Decision Point, and does
// what the answer says. Deny by default; no answer is never an allow; an obligation it cannot meet is never ignored. The PDP
// itself (OPA, a product's own policy) sits behind PolicyDecisionPoint. First used by OneGreenHealth.
plugins {
    id("java-library")
}

dependencies {
    api("io.projectreactor:reactor-core:3.8.8")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.projectreactor:reactor-test:3.8.8")
}
