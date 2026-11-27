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

import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ask the person to consent to a purpose (the real adapter raises a request with the Consent Manager, which contacts them).
 * {@code subjectContactRef} is a reference to the person's contact details held by the Registry, never the number or
 * address itself.
 */
public record ConsentRequest(UUID tenantId, String subjectContactRef, PurposeCode purpose, int purposeVersion) {

    public ConsentRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Reference.opaque(subjectContactRef, "subjectContactRef");
        Objects.requireNonNull(purpose, "purpose");
        if (purposeVersion < 1) {
            throw new IllegalArgumentException("purposeVersion starts at 1");
        }
    }

    /** What a request gives back: the id to follow it by and where the person answers. */
    public sealed interface Result {

        record Accepted(String requestId, URI portalLink) implements Result {
            public Accepted {
                Reference.opaque(requestId, "requestId");
                Objects.requireNonNull(portalLink, "portalLink");
            }
        }

        record Refused(String reason) implements Result {
            private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,63}");

            public Refused {
                if (reason == null || !CODE.matcher(reason).matches()) {
                    throw new IllegalArgumentException("A refusal reason is a kebab-case code");
                }
            }
        }
    }
}
