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

package com.techeazyhq.kit.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimsParserTest {

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";

    private static Map<String, Object> full() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sub", "user-1");
        m.put("tenant_id", TENANT);
        m.put("facility_ids", List.of("f1", "f2"));
        m.put("roles", List.of("physician", "nurse"));
        m.put("practitioner_id", "p-77");
        m.put("hpr_id", "hpr@example.in");
        m.put("iss", "https://idp.example/realms/x");
        m.put("exp", 4102444800L);
        return m;
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> m = full();
        m.put(key, value);
        return m;
    }

    private static Map<String, Object> without(String key) {
        Map<String, Object> m = full();
        m.remove(key);
        return m;
    }

    private static String invalid(Map<String, ?> claims) {
        return assertInstanceOf(ClaimsParser.Invalid.class, ClaimsParser.parse(claims)).code();
    }

    private static Claims valid(Map<String, ?> claims) {
        return assertInstanceOf(ClaimsParser.Valid.class, ClaimsParser.parse(claims)).claims();
    }

    // ---- what a good token gives -----------------------------------------------------------------------------------------

    @Test
    void aFullTokenGivesEveryPartAndIgnoresWhatItDoesNotKnow() {
        Claims c = valid(full());

        assertEquals("user-1", c.subject());
        assertEquals(UUID.fromString(TENANT), c.tenantId());
        assertEquals(Set.of("f1", "f2"), c.facilityIds());
        assertEquals(Set.of("physician", "nurse"), c.roles());
        assertEquals("p-77", c.practitionerId());
        assertEquals("hpr@example.in", c.hprId());
        assertNull(c.purpose(), "the purpose is a statement of the request, never in the token");
        assertTrue(c.hasRole("nurse") && c.worksAt("f2") && c.isPractitioner());
    }

    @Test
    void aMinimalTokenIsAPersonOfATenantWhoIsAllowedNothingYet() {
        Claims c = valid(Map.of("sub", "user-2", "tenant_id", TENANT));

        assertEquals(Set.of(), c.facilityIds());
        assertEquals(Set.of(), c.roles());
        assertNull(c.practitionerId());
        assertNull(c.hprId());
        assertFalse(c.isPractitioner());
    }

    @Test
    void aSingleStringIsASetOfOneForFacilitiesAndRoles() {
        Map<String, Object> single = with("roles", "physician");
        single.put("facility_ids", "f9");
        Claims c = valid(single);

        assertEquals(Set.of("physician"), c.roles());
        assertEquals(Set.of("f9"), c.facilityIds());
    }

    @Test
    void theTenantIsAUuidInItsFullFormInAnyCase() {
        assertEquals(UUID.fromString(TENANT), valid(with("tenant_id", TENANT.toUpperCase())).tenantId());
    }

    @Test
    void theParsedSetsAreImmutableAndKeepTheirOrder() {
        Claims c = valid(full());

        assertEquals(List.of("f1", "f2"), new ArrayList<>(c.facilityIds()));
        assertThrows(UnsupportedOperationException.class, () -> c.roles().add("admin"));
    }

    // ---- the tenant is mandatory and exact ---------------------------------------------------------------------------------

    @Test
    void noTenantIsNotADefaultTenant() {
        assertEquals("no-tenant", invalid(without("tenant_id")));
        assertEquals("no-tenant", invalid(with("tenant_id", null)));
    }

    @Test
    void aTenantThatIsNotAFullUuidIsRefused() {
        for (Object bad : new Object[] {"", " ", "tenant-a", "1-1-1-1-1", TENANT + "x", " " + TENANT, 42, List.of(TENANT),
                Map.of("id", TENANT), "11111111111141118111111111111111"}) {
            assertEquals("bad-tenant", invalid(with("tenant_id", bad)), String.valueOf(bad));
        }
    }

    @Test
    void aSubjectIsMandatoryAndAPlainToken() {
        assertEquals("no-subject", invalid(without("sub")));
        for (Object bad : new Object[] {"", "  ", 7, "has space", "line\nbreak", "a".repeat(129), List.of("u")}) {
            assertEquals("no-subject", invalid(with("sub", bad)), String.valueOf(bad));
        }
    }

    // ---- facilities and roles ----------------------------------------------------------------------------------------------

    @Test
    void facilitiesAndRolesMustBePlainTokensOfTheRightType() {
        for (Object bad : new Object[] {List.of("ok", "has space"), List.of("ok", 5), List.of("a\nb"), 5, Map.of("a", "b"),
                List.of(""), List.of(List.of("nested")), "two words"}) {
            assertEquals("bad-facilities", invalid(with("facility_ids", bad)), String.valueOf(bad));
            assertEquals("bad-roles", invalid(with("roles", bad)), String.valueOf(bad));
        }
    }

    @Test
    void thereAreLimitsOnHowManyFacilitiesAndRolesAToken() {
        List<String> manyFacilities = new ArrayList<>();
        for (int i = 0; i <= Claims.MAX_FACILITIES; i++) {
            manyFacilities.add("f" + i);
        }
        List<String> manyRoles = new ArrayList<>();
        for (int i = 0; i <= Claims.MAX_ROLES; i++) {
            manyRoles.add("r" + i);
        }

        assertEquals("bad-facilities", invalid(with("facility_ids", manyFacilities)));
        assertEquals("bad-roles", invalid(with("roles", manyRoles)));
        assertEquals(Claims.MAX_FACILITIES, valid(with("facility_ids", manyFacilities.subList(0, Claims.MAX_FACILITIES))).facilityIds().size());
    }

    @Test
    void rolesCanBeAtAnotherPathSuchAsKeycloaksRealmAccess() {
        Map<String, Object> kc = without("roles");
        kc.put("realm_access", Map.of("roles", List.of("physician")));

        assertEquals(Set.of("physician"), assertInstanceOf(ClaimsParser.Valid.class,
                ClaimsParser.parse(kc, ClaimNames.defaults().keycloakRealmRoles())).claims().roles());
        assertEquals(Set.of(), valid(kc).roles(), "with the default names there is no such claim");
        assertEquals("bad-roles", assertInstanceOf(ClaimsParser.Invalid.class, ClaimsParser.parse(
                with("realm_access", Map.of("roles", "two words")), ClaimNames.defaults().keycloakRealmRoles())).code());
    }

    @Test
    void aProductCanNameTheClaimsItsIdentityProviderUses() {
        ClaimNames names = new ClaimNames("uid", "org", "sites", List.of("groups"), "doc", "reg");
        Map<String, Object> token = Map.of("uid", "u1", "org", TENANT, "sites", List.of("s1"), "groups", List.of("g1"), "doc", "d1",
                "reg", "r1");

        Claims c = assertInstanceOf(ClaimsParser.Valid.class, ClaimsParser.parse(token, names)).claims();

        assertEquals("u1", c.subject());
        assertEquals(Set.of("s1"), c.facilityIds());
        assertEquals(Set.of("g1"), c.roles());
        assertEquals("d1", c.practitionerId());
        assertEquals("r1", c.hprId());
    }

    // ---- practitioner and the registry placeholder -------------------------------------------------------------------------

    @Test
    void thePractitionerAndTheRegistryIdAreOptionalButMustBePlainTokensWhenPresent() {
        assertNull(valid(without("practitioner_id")).practitionerId());
        assertNull(valid(with("hpr_id", null)).hprId());
        for (Object bad : new Object[] {"", "a b", 3, List.of("p"), "x".repeat(129)}) {
            assertEquals("bad-practitioner", invalid(with("practitioner_id", bad)), String.valueOf(bad));
            assertEquals("bad-registry-id", invalid(with("hpr_id", bad)), String.valueOf(bad));
        }
    }

    // ---- it never throws, and never repeats what it was given ---------------------------------------------------------------

    @Test
    void nothingIsClaimsAtAll() {
        assertEquals("no-claims", invalid(null));
        assertEquals("no-claims", invalid(Map.of()));
    }

    @Test
    void whateverItIsGivenTheAnswerIsAResultAndNeverAnExceptionOrTheHostileText() {
        Random random = new Random(1);
        Object[] pool = {null, "", " ", "x", TENANT, "line\nbreak", 1, 2.5, true, List.of(), List.of("a"), List.of(1, 2),
                Map.of(), Map.of("k", "v"), new int[] {1}, "\u0000", "a".repeat(10_000)};
        String[] keys = {"sub", "tenant_id", "facility_ids", "roles", "practitioner_id", "hpr_id", "realm_access"};
        for (int i = 0; i < 5_000; i++) {
            Map<String, Object> m = new HashMap<>();
            for (String key : keys) {
                if (random.nextInt(3) > 0) {
                    m.put(key, pool[random.nextInt(pool.length)]);
                }
            }
            ClaimsParser.Result result = ClaimsParser.parse(m);

            if (result instanceof ClaimsParser.Invalid invalid) {
                assertTrue(invalid.code().matches("[a-z-]+"), "a code, never the value");
            } else {
                assertEquals(UUID.fromString(TENANT), ((ClaimsParser.Valid) result).claims().tenantId());
            }
        }
    }

    // ---- the value itself --------------------------------------------------------------------------------------------------

    @Test
    void theValueRefusesWhatTheParserWouldRefuse() {
        UUID t = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new Claims("a b", t, Set.of(), Set.of(), null, null, null));
        assertThrows(NullPointerException.class, () -> new Claims("u", null, Set.of(), Set.of(), null, null, null));
        assertThrows(NullPointerException.class, () -> new Claims("u", t, null, Set.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Claims("u", t, Set.of("a\nb"), Set.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Claims("u", t, Set.of(), Set.of(), "p q", null, null));
    }

    @Test
    void thePurposeIsACodeAndCanBeAddedForARequest() {
        Claims c = valid(full()).withPurpose("CARE_RECORD");

        assertEquals("CARE_RECORD", c.purpose());
        assertNull(valid(full()).purpose(), "the original is unchanged");
        for (String bad : new String[] {"care_record", "Care Record", "AB", "X".repeat(65), "", "CARE-RECORD"}) {
            assertThrows(IllegalArgumentException.class, () -> valid(full()).withPurpose(bad), bad);
        }
        assertNull(valid(full()).withPurpose(null).purpose());
    }

    @Test
    void toStringShowsIdsAndCountsAndNeverTheRegistryId() {
        String text = valid(full()).withPurpose("CARE_RECORD").toString();

        assertTrue(text.contains("user-1") && text.contains(TENANT) && text.contains("CARE_RECORD"));
        assertFalse(text.contains("hpr@example.in") || text.contains("p-77"));
    }

    @Test
    void claimsAreEqualWhenTheirPartsAre() {
        assertEquals(valid(full()), valid(full()));
        assertFalse(valid(full()).equals(valid(with("roles", List.of("nurse")))));
    }
}
