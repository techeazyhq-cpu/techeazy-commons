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

import java.time.Duration;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Lets a query see the change the same client has just made, though the projection is built asynchronously. It waits, up to a
 * limit, for the projection to reach the token's version, polling its position; when the limit passes it answers
 * {@link Freshness#STALE} and the caller falls back to the command side. It never waits forever.
 */
public final class ReadYourWrites {

    private final ProjectionPositions positions;
    private final String projection;
    private final Duration maxWait;
    private final Duration poll;

    public ReadYourWrites(ProjectionPositions positions, String projection, Duration maxWait, Duration poll) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.projection = Objects.requireNonNull(projection, "projection");
        if (maxWait.isNegative() || poll.isZero() || poll.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative and poll must be positive");
        }
        this.maxWait = maxWait;
        this.poll = poll;
    }

    public Mono<Freshness> await(VersionToken token) {
        Mono<Boolean> reached = Mono.defer(() -> positions.appliedVersion(projection, token.aggregate()))
                .map(applied -> applied >= token.version());
        if (maxWait.isZero()) {
            return reached.map(yes -> yes ? Freshness.FRESH : Freshness.STALE).onErrorReturn(Freshness.STALE);
        }
        return Flux.interval(Duration.ZERO, poll)
                .concatMap(tick -> reached)
                .filter(yes -> yes)
                .next()
                .map(yes -> Freshness.FRESH)
                .timeout(maxWait, Mono.just(Freshness.STALE))
                .onErrorReturn(Freshness.STALE);
    }
}
