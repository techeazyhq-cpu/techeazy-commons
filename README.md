<!--
  Copyright 2026 Vasantha Kumar

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

  @author Vasantha Kumar <vasantha.kumar@hotmail.com>
-->

# techeazy-commons

Shared Java libraries, Gradle build conventions and tooling for every TechEazyHQ product (OneGreenHealth, the Consent Manager, notification-service and the products to come). Reusing one tested implementation saves time and cost, and keeps standards and behaviour consistent across active and future products.

Licensed under the [Apache License 2.0](LICENSE).

## What is here

| Path | What | Coordinates or command |
|---|---|---|
| `kit-dom` | Domain building blocks: aggregate root that collects domain events, the specification pattern | `com.techeazyhq.kit:kit-dom` |
| `kit-web` | Spring WebFlux cross-cutting: **RFC 9457 `problem+json` errors from a layered error dictionary**, FHIR `OperationOutcome` answers, correlation IDs | `com.techeazyhq.kit:kit-web` |
| `kit-test` | Architecture tests (the Clean Architecture dependency rule as a normal test) and a PostgreSQL Testcontainers base | `com.techeazyhq.kit:kit-test` |
| `kit-claims` | The caller's **claims** (tenant, roles, purpose of use, and the **product's own attributes**, such as a hospital's facilities or a bank's branches, each a named set of plain tokens) as one validated value, and a strict parser for the claims of an already verified token; the claim names and the attributes are configured per product and identity provider. No dependencies | `com.techeazyhq.kit:kit-claims` |
| `kit-latency` | A **latency test kit**: thread-safe timing samples with nearest-rank percentiles, SLO presets (p95 300 ms reads and 600 ms writes, p99 1 s and 2 s, with a 70% budget rule), a gate that fails a build when a service is too slow, and a per-step Markdown and JSON report. A test dependency only; no dependencies | `com.techeazyhq.kit:kit-latency` |
| `kit-synthetic` | The reserved **synthetic-subject identity space**: a synthetic id is a v8 UUID starting `53594e00` ("SYN"), its reference is `syn:<id>`; one definition of what "synthetic" means so generators, stubs and checks agree. No dependencies | `com.techeazyhq.kit:kit-synthetic` |
| `kit-consent` | The **Consent Port**: `check`, `request`, `onConsentEvent` with ids-only values and three answers (allow with its basis, deny with a reason code, unavailable); a fail-closed **enforcer** (only an allow proceeds, every failure is "try later"); a call-site **guard** with per-site purposes and timeouts; a contract test for every adapter (test fixtures). No consent feature: a product's adapter calls a Consent Manager | `com.techeazyhq.kit:kit-consent` |
| `kit-consent-web` | Purpose of use and consent **on the wire** (WebFlux): a filter that requires exactly one `X-Purpose-Of-Use` header on protected paths (default `/api/**`) and validates it against the product's `PurposeCatalogue`, failing closed with `purpose-required` or `purpose-invalid`; and the handler that answers a consent stop as a 403 `consent-denied` or a retryable 503 `consent-unavailable` with `Retry-After`. The four error codes are in kit-web's base dictionary | `com.techeazyhq.kit:kit-consent-web` |
| `kit-request-context` | **Who is calling, for the life of one request** (WebFlux): `RequestContext` (the claims of the verified token, the token and the correlation id, in the Reactor context, never a default tenant), a filter that puts it there after Spring Security, and `OutboundHttp`: HTTPS and HTTP/2 only, relaying the caller's token, correlation id and purpose to **internal hosts only** and failing if there is no context | `com.techeazyhq.kit:kit-request-context` |
| `kit-breakglass` | **Break-glass emergency access, after the access**: `BreakGlassRecord` (who, whose record, what, a reason code, the justification; the justification is sensitive data and never reaches an event, a log line or `toString()`; `toEvent()` is ids and codes only) and the `ReviewQueue` that holds every use until **someone other than the accessor** reviews it, due **24 hours** after the access, final, tenant-scoped; an unjustified one escalates. `InMemoryReviewQueue` is the reference adapter. Whether an override is allowed is the product's policy | `com.techeazyhq.kit:kit-breakglass` |
| `kit-access` | **The Policy Enforcement Point**: `PolicyEnforcementPoint.enforce(input)` asks a `PolicyDecisionPoint` and stops the request unless the answer is an allow. **Deny by default; no answer is never an allow** (an error, an empty or not-understood answer, a timeout and `undetermined` are `AccessUnavailableException`); an allow with an obligation nobody can meet is refused, and an `ObligationHandler` that fails stops the access (an access that could not be audited did not happen) | `com.techeazyhq.kit:kit-access` |
| `kit-access-web` | The PDP over HTTP: `OpaDecisionPoint` asks an OPA server or sidecar (cleartext only on the loopback), and `AccessProblemWebExceptionHandler` answers a deny as 403 `forbidden` (no reasons sent) and an unavailable decision as a retryable 503 `access-unavailable` with `Retry-After`. The new code is in kit-web's base dictionary | `com.techeazyhq.kit:kit-access-web` |
| `kit-fieldcrypt` | **Field-level encryption** for a product's most sensitive columns and document fields (PostgreSQL or MongoDB): AES-256-GCM envelope encryption with a fresh data key per value, wrapped under a **per-tenant key held outside the database** (a `KeyEncryptionKeys` port; a cloud KMS adapter implements it). The sealed value is bound to its key, column and row, so it does not open if moved; any change is refused with one error; **destroying the tenant key erases the data** (crypto-shredding, backups included). `BlindIndex` gives equality search without revealing values | `com.techeazyhq.kit:kit-fieldcrypt` |
| `kit-leakcheck` | **The leak test**: plant recognisable sensitive values (`Seeds`) in a service's tests, collect what its logs, events, traces and error bodies say, and `LeakScanner.assertClean` fails when any seeded value is there, as written or mangled (lower case, URL-encoded, base64 at any alignment, hex, digits only, unicode escapes). A failure names the surface, the label and the class, never the value. A check with nothing seeded refuses to pass. Source-agnostic: it scans the text a test collects | `com.techeazyhq.kit:kit-leakcheck` |
| `kit-cqrs` | **CQRS where reads and writes differ** (STD-CQRS-01 to 05): an `EventLog` that is also the transactional outbox (a change and its thin event commit together), an `OutboxRelay` that publishes in order, at least once, and stops at the first failure; `ProjectionRunner` builds read models **idempotently and in order per aggregate** (a missing event stops it, never skipped), atomically with its position, and **rebuilds from scratch** from the log, by itself when the projection logic version changes; `VersionToken` + `ReadYourWrites` so a query waits (bounded) for its own write or says STALE; `Reconciliation` compares a projection with its source (keys, never values); lag for both. In-memory reference adapters. No event sourcing assumed | `com.techeazyhq.kit:kit-cqrs` |
| `kit-cqrs-r2dbc` | The PostgreSQL (R2DBC) adapters: `CqrsSchema.DDL` for the log, relay cursor and projection positions; `R2dbcEventLog` appends on the caller's transaction and **serialises appends with an advisory lock so the sequence is the commit order** (no event commits late behind a higher one); a second writer of a version, or a gap, is a `VersionConflictException`; compare-and-set cursor; positions that commit with the projection | `com.techeazyhq.kit:kit-cqrs-r2dbc` |
| `build-logic` | Gradle convention plugins: `techeazy.spring-service`, `techeazy.clean-arch` | plugin ids with the same names |
| `tools/error-dictionary` | CI check and catalogue generator for a product's error dictionary | `node tools/error-dictionary/error-dictionary.mjs --config <file>` |
| `tools/license-headers` | Checks (and with `--fix` adds) the Apache 2.0 header on every tracked source file | `node tools/license-headers/license-headers.mjs` |
| `infra/maven-repo` | A local Maven repository in Docker for these libraries | `docker compose -f infra/maven-repo/compose.yml up -d` |

## Build and test

Requires JDK 21 and, for the tools, Node 20 or newer. Docker is needed only for the local Maven repository and for the Testcontainers-based tests in products.

```bash
./gradlew build                                              # compile, test, javadoc and sources jars
node --test tools/error-dictionary/error-dictionary.test.mjs
```

## Use it in a product

The libraries are distributed through a local Maven repository in Docker, see [docs/distribution.md](docs/distribution.md). In short:

```kotlin
// settings.gradle.kts of the product
pluginManagement {
    repositories {
        maven { url = uri("http://localhost:8083/snapshots"); isAllowInsecureProtocol = true }
        gradlePluginPortal()
        mavenCentral()
    }
}

// a service module's build.gradle.kts
plugins {
    id("techeazy.spring-service") version "0.1.0-SNAPSHOT"
    id("techeazy.clean-arch") version "0.1.0-SNAPSHOT"
}
dependencies {
    implementation("com.techeazyhq.kit:kit-web:0.1.0-SNAPSHOT")
    implementation("com.techeazyhq.kit:kit-dom:0.1.0-SNAPSHOT")
}
```

Standards that every product follows through these libraries are in [docs/standards](docs/standards).

## Rules for this repository

1. **Nothing product-specific.** No product names, tenant concepts or domain rules; those belong in the product.
2. **Backward compatible within a major version.** Released error codes, plugin ids and public types do not change; deprecate instead.
3. **Tested.** Every library has tests; every tool has tests; architecture rules run as tests.
4. **Apache 2.0 header on every source file**, enforced by `tools/license-headers`.
5. **Changes by pull request** from a feature branch.
