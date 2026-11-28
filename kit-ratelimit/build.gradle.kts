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

// Keeps one tenant, or one stolen token, from starving the others (STD-NFR, TM-14): a token bucket per tenant and per caller, answered
// as a 429 problem with Retry-After; and a time budget per request. Per instance, in memory: with N instances the allowance is
// about N times the limit, and a shared store (Redis) is the next step behind the same interface. First used by OneGreenHealth.
plugins {
    id("java-library")
}

dependencies {
    api(project(":kit-request-context"))
    api(project(":kit-web"))

    compileOnly("org.springframework.boot:spring-boot-starter-webflux:4.0.9")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux:4.0.9")
    testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server:4.0.9")
    testImplementation("org.springframework.boot:spring-boot-jackson2:4.0.9")
    testImplementation("org.springframework:spring-test:7.0.10")
    testImplementation("io.projectreactor:reactor-test:3.8.8")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
