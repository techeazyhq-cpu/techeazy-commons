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

// CQRS where reads and writes differ: the command side changes an aggregate and records a thin event in the same transaction
// (the transactional outbox); a relay publishes the events in order; the query side builds projections from the events,
// idempotently and in order per aggregate, and can rebuild from scratch by replaying them. A command returns a version token,
// so a query can wait for its own write (read-your-writes) or fall back to the command side. Store-agnostic ports with in-memory
// reference adapters here and a PostgreSQL (R2DBC) adapter in kit-cqrs-r2dbc. No event sourcing is assumed: aggregates are state
// stored, and the log is what projections are built from. First used by OneGreenHealth.
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
