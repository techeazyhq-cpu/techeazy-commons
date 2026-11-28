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

package com.techeazyhq.kit.cqrs;

import static com.techeazyhq.kit.cqrs.Fixtures.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ReadYourWritesTest {

    private static final AggregateKey ITEM = new AggregateKey(TENANT, "item", "a");

    private final InMemoryProjectionPositions positions = new InMemoryProjectionPositions();
    private final ReadYourWrites ryw = new ReadYourWrites(positions, "names", Duration.ofSeconds(2), Duration.ofMillis(100));

    @Test
    void aWriteTheProjectionAlreadyHasIsFreshAtOnce() {
        positions.record("names", 1, ITEM, 3, 3).block();

        StepVerifier.create(ryw.await(new VersionToken(ITEM, 3))).expectNext(Freshness.FRESH).verifyComplete();
        StepVerifier.create(ryw.await(new VersionToken(ITEM, 2))).expectNext(Freshness.FRESH).verifyComplete();
    }

    @Test
    void aQueryWaitsForTheProjectionToCatchUp() {
        StepVerifier.withVirtualTime(() -> ryw.await(new VersionToken(ITEM, 1)))
                .expectSubscription()
                .thenAwait(Duration.ofMillis(300))
                .then(() -> positions.record("names", 1, ITEM, 1, 1).block())
                .thenAwait(Duration.ofMillis(200))
                .expectNext(Freshness.FRESH)
                .verifyComplete();
    }

    @Test
    void whenItNeverCatchesUpTheAnswerIsStaleAfterTheLimitAndNeverWaitsLonger() {
        StepVerifier.withVirtualTime(() -> ryw.await(new VersionToken(ITEM, 5)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(3))
                .expectNext(Freshness.STALE)
                .verifyComplete();
    }

    @Test
    void anotherAggregateOrAnotherTenantIsNotTheSameWrite() {
        positions.record("names", 1, ITEM, 9, 9).block();

        StepVerifier.withVirtualTime(() -> ryw.await(new VersionToken(new AggregateKey(TENANT, "item", "b"), 1)))
                .thenAwait(Duration.ofSeconds(3)).expectNext(Freshness.STALE).verifyComplete();
        StepVerifier.withVirtualTime(() -> ryw.await(new VersionToken(
                new AggregateKey(java.util.UUID.randomUUID(), "item", "a"), 1)))
                .thenAwait(Duration.ofSeconds(3)).expectNext(Freshness.STALE).verifyComplete();
    }

    @Test
    void aTokenRoundTripsAndOnlyOurOwnTokensParse() {
        VersionToken token = new VersionToken(new AggregateKey(TENANT, "item", "a.b:c-1"), 42);

        assertEquals(Optional.of(token), VersionToken.parse(token.encode()));
        assertTrue(token.encode().startsWith("rw1." + TENANT + ".item."));
        for (String bad : new String[] {null, "", "rw1", "rw2." + TENANT + ".item.a.1", "rw1.not-a-uuid.item.a.1",
                "rw1." + TENANT + ".item.a.x", "rw1." + TENANT + ".item.a.0", "rw1." + TENANT + ".item.a", "garbage"}) {
            assertFalse(VersionToken.parse(bad).isPresent(), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new VersionToken(ITEM, 0));
    }

    @Test
    void theLimitsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new ReadYourWrites(positions, "n", Duration.ofSeconds(1), Duration.ZERO));
    }
}
