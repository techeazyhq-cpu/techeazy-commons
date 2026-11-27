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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ConsentPort} adapter must do, whatever it is built on. A stub passes it; so does an adapter to a real
 * Consent Manager, with a subject and a purpose its test double knows. The adapter's own test supplies
 * the port and a subject reference the adapter accepts.
 */
public abstract class ConsentPortContract {

    protected static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    protected static final PurposeCode PURPOSE = PurposeCode.of("CARE_DELIVERY");

    protected abstract ConsentPort port();

    /** A subject reference this adapter is able to decide on. */
    protected abstract String knownSubjectRef();

    /** A well-formed subject reference this adapter must not allow processing for. */
    protected abstract String unknownSubjectRef();

    @Test
    void aCheckAlwaysAnswersOneOfTheThreeDecisionsAndNeverCompletesEmpty() {
        for (String subject : new String[] {knownSubjectRef(), unknownSubjectRef()}) {
            ConsentDecision decision = port().check(new ConsentCheck(TENANT, subject, PURPOSE)).block();

            assertNotNull(decision, "a check never completes empty");
            assertTrue(decision instanceof ConsentDecision.Allow || decision instanceof ConsentDecision.Deny
                    || decision instanceof ConsentDecision.Unavailable);
        }
    }

    @Test
    void anAllowNamesItsBasisSoTheCallerCanAuditIt() {
        ConsentDecision decision = port().check(new ConsentCheck(TENANT, knownSubjectRef(), PURPOSE)).block();

        if (decision instanceof ConsentDecision.Allow allow) {
            assertNotNull(allow.basis());
        }
    }

    @Test
    void aSubjectTheAdapterCannotVouchForIsNeverAllowed() {
        ConsentDecision decision = port().check(new ConsentCheck(TENANT, unknownSubjectRef(), PURPOSE)).block();

        assertTrue(!decision.permitsProcessing(), "unknown subject got " + decision);
    }

    @Test
    void onlyAnAllowPermitsProcessingAndUnavailableIsNeitherAllowNorDeny() {
        assertTrue(new ConsentDecision.Allow(ConsentDecision.Basis.LIVE).permitsProcessing());
        assertTrue(!new ConsentDecision.Deny("no-consent").permitsProcessing());
        assertTrue(!new ConsentDecision.Unavailable(java.time.Duration.ofSeconds(30)).permitsProcessing());
    }

    @Test
    void aCallThatCannotBeUnderstoodIsRejectedBeforeItReachesTheAdapter() {
        assertThrows(NullPointerException.class, () -> new ConsentCheck(null, knownSubjectRef(), PURPOSE));
        assertThrows(IllegalArgumentException.class, () -> new ConsentCheck(TENANT, "Asha Rao, diabetic", PURPOSE));
        assertThrows(IllegalArgumentException.class, () -> PurposeCode.of("treat my diabetes"));
    }

    @Test
    void aConsentEventIsTakenNoteOfWithoutFailing() {
        ConsentEvent withdrawn = new ConsentEvent(TENANT, knownSubjectRef(), PURPOSE, ConsentEvent.Type.WITHDRAWN,
                "art-1", Instant.parse("2026-11-01T10:00:00Z"));

        assertDoesNotThrow(() -> port().onConsentEvent(withdrawn).block());
    }

    @Test
    void aRequestAnswersAcceptedOrRefusedNeverEmpty() {
        ConsentRequest.Result result = port().request(new ConsentRequest(TENANT, knownSubjectRef(), PURPOSE, 1)).block();

        assertNotNull(result);
    }
}
