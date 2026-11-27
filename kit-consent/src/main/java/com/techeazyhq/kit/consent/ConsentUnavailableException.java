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

package com.techeazyhq.kit.consent;

import java.time.Duration;

/**
 * Nobody could say ({@link ConsentOutcome.TryLater}): the consent check was unavailable, failed or timed out. It is not a
 * refusal, so the person is not asked again, and it is never read as an allow. The API answers 503
 * {@code consent-unavailable} with a {@code Retry-After}.
 */
public final class ConsentUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public ConsentUnavailableException(Duration retryAfter) {
        super("consent check unavailable, retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
