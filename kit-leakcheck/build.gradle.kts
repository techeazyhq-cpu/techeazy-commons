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

// Leak check: seed a service's tests with recognisable sensitive values, run the flows, and fail the test when any seeded value
// turns up in a log line, an event, a trace attribute or an error message, in the form it was written or in a form it is
// commonly mangled into (lower case, URL-encoded, base64, hex, digits only). Source-agnostic: it scans the text the test
// collected, so it works with whatever captures logs, spans and events in a product. First used by OneGreenHealth.
plugins {
    id("java-library")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
