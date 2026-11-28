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

package com.techeazyhq.kit.requestcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboundTargetTest {

    @Test
    void theSettingNamesHostsAudiencesAndScopes() {
        Map<String, OutboundTarget> targets = OutboundTarget.parse("clinical.internal=ogh-clinical-svc#read+write, Accounts.internal=ogh-accounts-svc");

        assertEquals(Set.of("clinical.internal", "accounts.internal"), targets.keySet());
        assertEquals(Set.of("read", "write"), targets.get("clinical.internal").scopes());
        assertEquals("ogh-accounts-svc", targets.get("accounts.internal").audience());
        assertEquals(Set.of(), targets.get("accounts.internal").scopes());
    }

    @Test
    void anEmptySettingIsNoInternalService() {
        assertTrue(OutboundTarget.parse("").isEmpty());
        assertTrue(OutboundTarget.parse(null).isEmpty());
        assertTrue(OutboundTarget.parse("  ").isEmpty());
    }

    @Test
    void aTypoIsAnErrorNotAServiceSilentlyDropped() {
        for (String bad : new String[] {"clinical.internal", "=aud", "host=", "bad host=aud", "h=a b", "h=aud#sc ope", "h=aud,h=other"}) {
            assertThrows(IllegalArgumentException.class, () -> OutboundTarget.parse(bad), bad);
        }
    }
}
