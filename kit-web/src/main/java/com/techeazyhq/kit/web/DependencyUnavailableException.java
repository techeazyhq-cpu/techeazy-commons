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

package com.techeazyhq.kit.web;

import java.time.Duration;

/**
 * A service this one depends on cannot be used right now: it did not answer in time, it kept failing, or the circuit to it is open.
 * It is the platform's one way to say so. The API answers a retryable 503 {@code dependency-unavailable} with {@code Retry-After}; it
 * is not a decision about the request, and the answer never names the dependency.
 */
public final class DependencyUnavailableException extends RuntimeException {

    private final transient Duration retryAfter;

    public DependencyUnavailableException(String dependency, Duration retryAfter, Throwable cause) {
        super("dependency unavailable: " + dependency, cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
