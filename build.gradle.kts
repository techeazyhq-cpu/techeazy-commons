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


// Shared libraries for every TechEazyHQ product. Released as com.techeazyhq.kit:<module>:<version> to the local
// Docker Maven repository (docs/distribution.md); `./gradlew publishToMavenLocal` works with no Docker at all.

allprojects {
    group = "com.techeazyhq.kit"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "jacoco")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Testcontainers 1.x asks for Docker API 1.32 by default; Docker Engine 29 and newer refuse anything below 1.40.
        systemProperty("api.version", "1.44")
    }

    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("TechEazyHQ shared library: ${project.name}")
                    url.set("https://github.com/techeazyhq-cpu/techeazy-commons")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers {
                        developer {
                            name.set("Vasantha Kumar")
                            email.set("vasantha.kumar@hotmail.com")
                        }
                    }
                    scm {
                        url.set("https://github.com/techeazyhq-cpu/techeazy-commons")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "localDocker"
                val base = providers.gradleProperty("techeazy.repo.url").orElse("http://localhost:8083").get()
                url = uri(if (version.toString().endsWith("SNAPSHOT")) "$base/snapshots" else "$base/releases")
                isAllowInsecureProtocol = true
                credentials {
                    username = providers.environmentVariable("TECHEAZY_REPO_USER").orElse("admin").get()
                    password = providers.environmentVariable("TECHEAZY_REPO_PASSWORD").orElse("").get()
                }
            }
        }
    }
}
