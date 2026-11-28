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

// The PostgreSQL (R2DBC) store of kit-saga: one table, one row per saga, changed by compare-and-set on its version in single
// statements, so no transaction is ever open while a step calls another service. First used by OneGreenHealth.
plugins {
    id("java-library")
}

dependencies {
    api(project(":kit-saga"))

    compileOnly("org.springframework:spring-r2dbc:7.0.10")

    testImplementation(project(":kit-test"))
    testImplementation("org.springframework:spring-r2dbc:7.0.10")
    testImplementation("org.springframework:spring-tx:7.0.10")
    testImplementation("org.postgresql:r2dbc-postgresql:1.1.3.RELEASE")
    testImplementation("org.postgresql:postgresql:42.7.14")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.projectreactor:reactor-test:3.8.8")
}
