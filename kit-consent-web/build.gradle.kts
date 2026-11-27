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

// Purpose of use and consent on the wire (WebFlux): a filter that requires and validates the X-Purpose-Of-Use header against a
// product's catalogue of purposes, and the handler that answers a consent stop as a 403 or a retryable 503 problem. The error
// codes it raises are in kit-web's base dictionary. Like kit-web, Spring Boot is the product's to provide. First used by
// OneGreenHealth.
plugins {
    id("java-library")
}

dependencies {
    api(project(":kit-consent"))
    api(project(":kit-web"))

    compileOnly("org.springframework.boot:spring-boot-starter-webflux:4.0.9")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux:4.0.9")
    testImplementation("org.springframework.boot:spring-boot-jackson2:4.0.9")
    testImplementation("org.springframework:spring-test:7.0.10")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
