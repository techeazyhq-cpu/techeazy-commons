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

// How a service decides a bearer token is one it accepts: the signature (from the identity provider's keys), the time, **who issued
// it** and **who it is for**. A token that verifies but was minted for another client of the same realm, or by another issuer, is
// refused. A service that is not told its issuer and its audience refuses to start, so no deployment runs without the check.
// First used by OneGreenHealth.
plugins {
    id("java-library")
}

dependencies {
    compileOnly("org.springframework.boot:spring-boot-starter-oauth2-resource-server:4.0.9")

    testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server:4.0.9")
    testImplementation("io.projectreactor:reactor-test:3.8.8")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
