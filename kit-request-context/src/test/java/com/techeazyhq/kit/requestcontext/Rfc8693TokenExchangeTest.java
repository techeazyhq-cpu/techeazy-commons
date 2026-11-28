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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techeazyhq.kit.claims.Claims;
import com.techeazyhq.kit.web.DependencyUnavailableException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The caller's token goes to the identity provider, which gives back one for the target. Against a real server on the loopback, so the
 * request that goes out is the one the provider would see; nothing of the provider's answer is repeated in a message.
 */
class Rfc8693TokenExchangeTest {

    private static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-28T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static DisposableServer idp;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> FORM = new AtomicReference<>();
    private static final AtomicReference<String> NEXT_ANSWER = new AtomicReference<>();
    private static final AtomicInteger NEXT_STATUS = new AtomicInteger(200);

    @BeforeAll
    static void start() {
        idp = HttpServer.create().host("localhost").port(0).route(routes -> routes.post("/token", (request, response) -> {
            CALLS.incrementAndGet();
            AUTHORIZATION.set(request.requestHeaders().get("Authorization"));
            return request.receive().aggregate().asString().flatMap(body -> {
                FORM.set(body);
                return response.status(NEXT_STATUS.get()).header("Content-Type", "application/json").sendString(Mono.just(NEXT_ANSWER.get())).then();
            });
        })).bindNow();
    }

    @AfterAll
    static void stop() {
        idp.disposeNow();
    }

    private final MovingClock clock = new MovingClock();

    @BeforeEach
    void reset() {
        CALLS.set(0);
        AUTHORIZATION.set(null);
        FORM.set(null);
        NEXT_STATUS.set(200);
        NEXT_ANSWER.set("{\"access_token\":\"narrow-1\",\"token_type\":\"Bearer\",\"expires_in\":300}");
    }

    private Rfc8693TokenExchange exchange() {
        return new Rfc8693TokenExchange(WebClient.builder(), "http://localhost:" + idp.port() + "/token", "gateway", "the-secret", clock);
    }

    private static RequestContext caller(String token) {
        return new RequestContext("corr-1", token, new Claims("u1", UUID.randomUUID(), Set.of("staff")));
    }

    private static String field(String form, String name) {
        for (String pair : form.split("&")) {
            if (pair.startsWith(name + "=")) {
                return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void itAsksTheProviderForATokenForTheTargetAsTheTokenExchangeGrantAndAuthenticatesAsTheService() {
        assertEquals("narrow-1", exchange().exchange(caller("callers-token"), OutboundTarget.of("clinical-svc", "write", "read")).block());

        assertEquals("Basic " + Base64.getEncoder().encodeToString("gateway:the-secret".getBytes(StandardCharsets.UTF_8)), AUTHORIZATION.get());
        assertEquals("urn:ietf:params:oauth:grant-type:token-exchange", field(FORM.get(), "grant_type"));
        assertEquals("callers-token", field(FORM.get(), "subject_token"));
        assertEquals("urn:ietf:params:oauth:token-type:access_token", field(FORM.get(), "subject_token_type"));
        assertEquals("clinical-svc", field(FORM.get(), "audience"));
        assertEquals("read write", field(FORM.get(), "scope"), "the scopes of the call, in a fixed order");
    }

    @Test
    void theExchangedTokenIsKeptUntilShortlyBeforeItExpiresPerCallerAudienceAndScopes() {
        Rfc8693TokenExchange exchange = exchange();
        RequestContext alice = caller("alice-token");
        OutboundTarget clinical = OutboundTarget.of("clinical-svc", "read");

        exchange.exchange(alice, clinical).block();
        exchange.exchange(alice, clinical).block();
        assertEquals(1, CALLS.get(), "the second call needed no trip to the provider");

        exchange.exchange(caller("bob-token"), clinical).block();
        exchange.exchange(alice, OutboundTarget.of("accounts-svc", "read")).block();
        exchange.exchange(alice, OutboundTarget.of("clinical-svc", "write")).block();
        assertEquals(4, CALLS.get(), "another caller, audience or scope set is another token");

        clock.now = clock.now.plus(Duration.ofSeconds(295));
        exchange.exchange(alice, clinical).block();
        assertEquals(5, CALLS.get(), "within ten seconds of expiry it is exchanged again, never used on its last second");
    }

    @Test
    void aProviderThatRefusesIsAConfigurationFaultNeverAnAllow() {
        NEXT_STATUS.set(403);
        NEXT_ANSWER.set("{\"error\":\"access_denied\",\"error_description\":\"client the-secret may not exchange callers-token\"}");

        TokenExchangeException failure = assertThrows(TokenExchangeException.class,
                () -> exchange().exchange(caller("callers-token"), OutboundTarget.of("clinical-svc")).block());

        assertFalse(String.valueOf(failure.getMessage()).contains("the-secret") || String.valueOf(failure.getMessage()).contains("callers-token"),
                "nothing the provider said, nothing secret, in the message: " + failure.getMessage());
    }

    @Test
    void aProviderThatIsDownOrFailingIsARetryableOutageNotAConfigurationFault() {
        NEXT_STATUS.set(503);
        NEXT_ANSWER.set("{}");

        assertThrows(DependencyUnavailableException.class, () -> exchange().exchange(caller("t"), OutboundTarget.of("clinical-svc")).block());
    }

    @Test
    void anAnswerThatIsNotATokenIsRefused() {
        for (String bad : new String[] {"{}", "{\"access_token\":\"\"}", "{\"access_token\":\"x\",\"token_type\":\"MAC\"}", "{\"access_token\":5}"}) {
            NEXT_ANSWER.set(bad);

            assertThrows(TokenExchangeException.class, () -> exchange().exchange(caller("t"), OutboundTarget.of("clinical-svc")).block(), bad);
        }
    }

    @Test
    void aTokenIsNeverKeptLongerThanTheProviderSaidOrFifteenMinutes() {
        NEXT_ANSWER.set("{\"access_token\":\"long\",\"token_type\":\"Bearer\",\"expires_in\":86400}");
        Rfc8693TokenExchange exchange = exchange();
        exchange.exchange(caller("t"), OutboundTarget.of("a")).block();

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        exchange.exchange(caller("t"), OutboundTarget.of("a")).block();

        assertEquals(2, CALLS.get());
    }

    @Test
    void theEndpointIsHttpsOrTheLoopbackAndTheServiceNeedsItsOwnCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new Rfc8693TokenExchange(WebClient.builder(), "http://idp.example/token", "g", "s", clock));
        assertThrows(IllegalArgumentException.class, () -> new Rfc8693TokenExchange(WebClient.builder(), "https://idp.example/token", "", "s", clock));
        assertThrows(IllegalArgumentException.class, () -> new Rfc8693TokenExchange(WebClient.builder(), "https://idp.example/token", "g", " ", clock));
        assertTrue(new Rfc8693TokenExchange(WebClient.builder(), "https://idp.example/token", "g", "s", clock) != null);
    }
}
