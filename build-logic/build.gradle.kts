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

plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.techeazyhq.kit"
version = "0.1.0-SNAPSHOT"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.0.9")
    implementation("io.spring.dependency-management:io.spring.dependency-management.gradle.plugin:1.1.7")
}

publishing {
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
