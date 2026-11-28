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

package com.techeazyhq.kit.access.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import reactor.core.publisher.Mono;

class OpaDecisionPointTest {

    private final AtomicReference<ClientRequest> sent = new AtomicReference<>();

    private OpaDecisionPoint opa(String base, HttpStatus status, String json) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            sent.set(request);
            return Mono.just(ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(json).build());
        });
        return new OpaDecisionPoint(builder, base, "acme/access/decision");
    }

    @Test
    void itAsksTheRuleWithTheInputAndReadsTheResult() {
        OpaDecisionPoint opa = opa("http://localhost:8181", HttpStatus.OK,
                "{\"result\":{\"allow\":true,\"undetermined\":false,\"reasons\":[],\"obligations\":{\"audit\":true}}}");

        StepVerifier.create(opa.decide(Map.of("action", "read"))).assertNext(d -> {
            assertTrue(d.allow());
            assertEquals(true, d.obligations().get("audit"));
        }).verifyComplete();
        assertEquals("http://localhost:8181/v1/data/acme/access/decision", sent.get().url().toString());
        assertEquals("POST", sent.get().method().name());
    }

    @Test
    void aDenyIsRead() {
        OpaDecisionPoint opa = opa("https://opa.internal", HttpStatus.OK,
                "{\"result\":{\"allow\":false,\"reasons\":[\"cross-tenant\"]}}");

        StepVerifier.create(opa.decide(Map.of())).assertNext(d -> assertEquals(java.util.List.of("cross-tenant"), d.reasons()))
                .verifyComplete();
    }

    @Test
    void anUndefinedRuleAnErrorStatusAndAnAnswerNotUnderstoodAreErrorsNeverAnAllow() {
        StepVerifier.create(opa("http://localhost:8181", HttpStatus.OK, "{}").decide(Map.of())).expectError().verify();
        StepVerifier.create(opa("http://localhost:8181", HttpStatus.INTERNAL_SERVER_ERROR, "{}").decide(Map.of())).expectError().verify();
        StepVerifier.create(opa("http://localhost:8181", HttpStatus.OK, "{\"result\":{\"allow\":\"yes\"}}").decide(Map.of()))
                .expectError().verify();
        StepVerifier.create(opa("http://localhost:8181", HttpStatus.OK, "{\"result\":true}").decide(Map.of())).expectError().verify();
    }

    @Test
    void cleartextIsForTheLoopbackOnly() {
        for (String ok : new String[] {"http://localhost:8181", "http://127.0.0.1:8181", "https://opa.internal"}) {
            opa(ok, HttpStatus.OK, "{}");
        }
        for (String bad : new String[] {"http://opa.internal:8181", "http://10.0.0.5:8181", "ftp://localhost", "opa.internal"}) {
            assertThrows(IllegalArgumentException.class, () -> opa(bad, HttpStatus.OK, "{}"), bad);
        }
    }

    @Test
    void theRulePathCannotBeUsedToReachAnotherEndpoint() {
        for (String bad : new String[] {"../admin", "acme/access?x=1", "", "a b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OpaDecisionPoint(WebClient.builder(), "http://localhost:8181", bad), bad);
        }
    }
}
