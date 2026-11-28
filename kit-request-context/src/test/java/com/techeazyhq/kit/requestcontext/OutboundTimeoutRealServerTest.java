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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** The Netty client itself, against a real server: a peer that accepts and never answers is cut at the response timeout. */
class OutboundTimeoutRealServerTest {

    private static DisposableServer hung;

    @BeforeAll
    static void start() {
        hung = HttpServer.create().port(0).handle((request, response) -> Mono.never()).bindNow();
    }

    @AfterAll
    static void stop() {
        hung.disposeNow();
    }

    @Test
    void aServerThatNeverAnswersIsGivenUpOnAtTheAttemptTimeoutNotLater() {
        OutboundPolicy quick = new OutboundPolicy(Duration.ofMillis(300), Duration.ofMillis(400), 0, Duration.ofMillis(50), Duration.ofMillis(100), 5,
                Duration.ofSeconds(30));
        // plain http, so the https-only filter of the builder is not used: this tests the Netty settings of the policy
        WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(OutboundHttp.httpClient(quick))).build();
        long started = System.nanoTime();

        try {
            client.get().uri("http://localhost:" + hung.port() + "/x").retrieve().toBodilessEntity().block(Duration.ofSeconds(10));
            throw new AssertionError("a hung server must not answer");
        } catch (RuntimeException expected) {
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertTrue(took.compareTo(Duration.ofSeconds(3)) < 0, "released at the timeout, not after " + took);
        }
    }

    @Test
    void aClosedPortFailsAtOnce() {
        OutboundPolicy quick = new OutboundPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ofMillis(50), Duration.ofMillis(100), 5,
                Duration.ofSeconds(30));
        WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(OutboundHttp.httpClient(quick))).build();
        long started = System.nanoTime();

        try {
            client.get().uri("http://localhost:1/x").retrieve().toBodilessEntity().block(Duration.ofSeconds(10));
            throw new AssertionError("nothing listens on port 1");
        } catch (RuntimeException expected) {
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        }
    }
}
