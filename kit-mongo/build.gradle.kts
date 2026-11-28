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

// Tenant-isolated MongoDB access (STD-DB-04): MongoDB has no row-level security, so the isolation is made in the data-access layer.
// Every operation takes the tenant and cannot be written without it; the raw collection is never handed out; filters that could
// run code or reach across tenants are refused; collections are created with a schema validator that requires the tenant and a
// unique index on it. A database per tenant is the stronger tier. Migrations are ordered, checksummed and run once. Reactive,
// over the MongoDB driver. First used by OneGreenHealth.
plugins {
    id("java-library")
}

dependencies {
    api("io.projectreactor:reactor-core:3.8.8")
    api("org.mongodb:mongodb-driver-reactivestreams:5.5.1")

    testImplementation(project(":kit-test"))
    testImplementation("org.testcontainers:mongodb")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.projectreactor:reactor-test:3.8.8")
}
