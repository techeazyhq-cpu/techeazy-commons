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

/**
 * Consent says no ({@link ConsentOutcome.Refused}), raised by {@link ConsentGuard} so that the handler stops and the API
 * answers 403 {@code consent-denied}. Carries the reason code only, never the subject or the purpose.
 */
public final class ConsentRefusedException extends RuntimeException {

    private final String reason;

    public ConsentRefusedException(String reason) {
        super("consent refused: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
