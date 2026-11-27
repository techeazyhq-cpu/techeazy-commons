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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The port is data-blind by construction: only opaque references and codes can be put in a call. */
class ConsentValuesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final PurposeCode PURPOSE = PurposeCode.of("CARE_DELIVERY");

    @Test
    void aSubjectReferenceIsOpaqueNeverAValueOrASentence() {
        assertDoesNotThrow(() -> new ConsentCheck(TENANT, "syn:53594e00-1ea5-84b9-bf70-44e1c9d3c684", PURPOSE));
        for (String bad : new String[] {null, "", " ", "Asha Rao", "has space", "name@example.com", "+919812345678?x",
                "diagnosis=diabetes", "a".repeat(129), "line\nbreak", "tab\t"}) {
            assertThrows(IllegalArgumentException.class, () -> new ConsentCheck(TENANT, bad, PURPOSE), "accepted: " + bad);
        }
    }

    @Test
    void aConsentReferenceIsOptionalButWhenGivenIsOpaque() {
        assertDoesNotThrow(() -> new ConsentCheck(TENANT, "subject-1", PURPOSE, null));
        assertDoesNotThrow(() -> new ConsentCheck(TENANT, "subject-1", PURPOSE, "art-42"));
        assertThrows(IllegalArgumentException.class, () -> new ConsentCheck(TENANT, "subject-1", PURPOSE, "free text here"));
    }

    @Test
    void aPurposeIsACodeNotText() {
        assertEquals("CARE_DELIVERY", PURPOSE.toString());
        for (String bad : new String[] {null, "", "ab", "care_delivery", "Care Delivery", "1CARE", "CARE-DELIVERY",
                "X".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> PurposeCode.of(bad), "accepted: " + bad);
        }
    }

    @Test
    void requestsAndEventsAreIdsOnlyToo() {
        assertThrows(IllegalArgumentException.class, () -> new ConsentRequest(TENANT, "9812345678 call after 5", PURPOSE, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConsentRequest(TENANT, "contact-1", PURPOSE, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConsentEvent(TENANT, "subject 1", PURPOSE,
                ConsentEvent.Type.GIVEN, "art-1", Instant.now()));
        assertThrows(NullPointerException.class, () -> new ConsentEvent(TENANT, "subject-1", PURPOSE, null, "art-1",
                Instant.now()));
    }

    @Test
    void withdrawalLapseAndRefusalStopProcessingTheOthersDoNot() {
        for (ConsentEvent.Type type : ConsentEvent.Type.values()) {
            boolean stops = type == ConsentEvent.Type.WITHDRAWN || type == ConsentEvent.Type.LAPSED
                    || type == ConsentEvent.Type.REFUSED;
            assertEquals(stops, new ConsentEvent(TENANT, "subject-1", PURPOSE, type, "art-1", Instant.now())
                    .stopsProcessing(), type.toString());
        }
    }

    @Test
    void aDecisionIsOneOfThreeAndOnlyAllowPermitsProcessing() {
        assertTrue(new ConsentDecision.Allow(ConsentDecision.Basis.STUB).permitsProcessing());
        assertFalse(new ConsentDecision.Deny("non-synthetic-subject").permitsProcessing());
        assertFalse(new ConsentDecision.Unavailable(Duration.ofSeconds(5)).permitsProcessing());
    }

    @Test
    void aDecisionCannotCarryWhatItShouldNot() {
        assertThrows(NullPointerException.class, () -> new ConsentDecision.Allow(null));
        assertThrows(IllegalArgumentException.class, () -> new ConsentDecision.Deny("Asha Rao refused"));
        assertThrows(IllegalArgumentException.class, () -> new ConsentDecision.Deny(null));
        assertThrows(IllegalArgumentException.class, () -> new ConsentDecision.Unavailable(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ConsentDecision.Unavailable(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ConsentDecision.Unavailable(null));
    }

    @Test
    void aRequestResultIsAcceptedWithAnIdAndALinkOrRefusedWithACode() {
        assertDoesNotThrow(() -> new ConsentRequest.Result.Accepted("req-1", URI.create("https://portal.invalid/r/req-1")));
        assertThrows(IllegalArgumentException.class, () -> new ConsentRequest.Result.Accepted("not opaque", URI.create("https://x.invalid")));
        assertThrows(NullPointerException.class, () -> new ConsentRequest.Result.Accepted("req-1", null));
        assertDoesNotThrow(() -> new ConsentRequest.Result.Refused("non-synthetic-subject"));
        assertThrows(IllegalArgumentException.class, () -> new ConsentRequest.Result.Refused("Because I said so"));
    }
}
