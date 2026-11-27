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

package com.techeazyhq.kit.access;

import java.time.Duration;

/**
 * Nobody could say: the decision point did not answer, answered something not understood, said "undetermined", or an
 * obligation could not be fulfilled. Not a refusal and never an allow. The API answers a retryable 503
 * {@code access-unavailable} with {@code Retry-After}.
 */
public final class AccessUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public AccessUnavailableException(String why, Duration retryAfter) {
        super("access unavailable (" + why + "), retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
