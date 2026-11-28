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

// Field-level encryption for the columns and document fields a product marks as its most sensitive: envelope encryption
// (AES-256-GCM, a fresh data key per value, wrapped under a per-tenant key held outside the database), bound to the row it
// belongs to, with blind indexes for equality search and crypto-shredding by destroying the tenant key. The key store is a port
// (a cloud KMS adapter implements it); an in-memory one is here for tests and local runs. Store-agnostic: the sealed value is
// a plain string for a PostgreSQL column or a MongoDB field. First used by OneGreenHealth.
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
