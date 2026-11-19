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

# Distribution: a local Maven repository in Docker

The libraries are published as `com.techeazyhq.kit:<module>:<version>` to a small Maven repository (Reposilite) that runs in Docker on the developer's machine. No GitHub CI or external registry is involved.

> **Dev only.** The repository listens on `127.0.0.1` over plain HTTP and is not meant to be exposed. When other people need the libraries (open-source contributors, other machines), publish tagged releases to Maven Central or JitPack as well; the coordinates stay the same.

## Start it

```bash
export TECHEAZY_REPO_PASSWORD='choose-a-local-secret'     # the admin token secret, never committed
docker compose -f infra/maven-repo/compose.yml up -d
```

Repositories (anyone on this machine may read, publishing needs the token):

| Repository | URL | Holds |
|---|---|---|
| snapshots | `http://localhost:8083/snapshots` | versions ending in `-SNAPSHOT` |
| releases | `http://localhost:8083/releases` | fixed versions |

## Publish

```bash
export TECHEAZY_REPO_PASSWORD='the same secret'
./gradlew publish :build-logic:publish
```

`./gradlew publishToMavenLocal :build-logic:publishToMavenLocal` publishes to `~/.m2` instead, with no Docker.

## Consume

Add the repository to the product's `settings.gradle.kts` (see the README), then depend on `com.techeazyhq.kit:kit-web:<version>` and apply the plugins by id and version.

## Versioning and releases

Semantic versioning. Day-to-day work uses `0.x.y-SNAPSHOT`. To release: set the version in `build.gradle.kts` and `build-logic/build.gradle.kts`, merge, tag `vX.Y.Z`, run `./gradlew publish :build-logic:publish`, then move on to the next snapshot. A released version is never overwritten.
