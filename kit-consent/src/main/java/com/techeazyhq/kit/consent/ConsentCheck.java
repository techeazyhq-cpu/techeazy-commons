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

import java.util.Objects;
import java.util.UUID;

/**
 * May this purpose be processed for this subject, now? Ids only: the tenant, an opaque subject reference, a purpose code
 * and optionally the reference of a consent already held (a token or artefact id).
 */
public record ConsentCheck(UUID tenantId, String subjectRef, PurposeCode purpose, String consentRef) {

    public ConsentCheck {
        Objects.requireNonNull(tenantId, "tenantId");
        Reference.opaque(subjectRef, "subjectRef");
        Objects.requireNonNull(purpose, "purpose");
        if (consentRef != null) {
            Reference.opaque(consentRef, "consentRef");
        }
    }

    public ConsentCheck(UUID tenantId, String subjectRef, PurposeCode purpose) {
        this(tenantId, subjectRef, purpose, null);
    }
}
