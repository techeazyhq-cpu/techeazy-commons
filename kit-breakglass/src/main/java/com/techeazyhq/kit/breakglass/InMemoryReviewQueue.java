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

package com.techeazyhq.kit.breakglass;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The reference {@link ReviewQueue}, in memory, for tests and local runs. Thread-safe. */
public final class InMemoryReviewQueue implements ReviewQueue {

    private final Clock clock;
    private final Map<UUID, ReviewItem> items = new ConcurrentHashMap<>();

    public InMemoryReviewQueue(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<ReviewItem> enqueue(BreakGlassRecord record) {
        Objects.requireNonNull(record, "record");
        return Mono.fromSupplier(() -> items.computeIfAbsent(record.id(), id -> ReviewItem.pending(record)));
    }

    @Override
    public Mono<ReviewItem> review(UUID tenantId, UUID itemId, UUID reviewerUserId, ReviewItem.Outcome outcome,
                                   String reviewerNote) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(reviewerUserId, "reviewerUserId");
        Objects.requireNonNull(outcome, "outcome");
        return Mono.fromSupplier(() -> items.compute(itemId, (id, current) -> {
            if (current == null || !current.record().tenantId().equals(tenantId)) {
                throw new ReviewRefused(ReviewRefused.NOT_FOUND);
            }
            if (!current.isPending()) {
                throw new ReviewRefused(ReviewRefused.ALREADY_REVIEWED);
            }
            if (current.record().accessorUserId().equals(reviewerUserId)) {
                throw new ReviewRefused(ReviewRefused.SELF_REVIEW);
            }
            return current.reviewed(reviewerUserId, outcome, reviewerNote, Instant.now(clock));
        }));
    }

    @Override
    public Mono<ReviewItem> find(UUID tenantId, UUID itemId) {
        return Mono.fromSupplier(() -> items.get(itemId)).filter(i -> i.record().tenantId().equals(tenantId));
    }

    @Override
    public Flux<ReviewItem> pending(UUID tenantId) {
        return select(tenantId, ReviewItem::isPending);
    }

    @Override
    public Flux<ReviewItem> overdue(UUID tenantId, Instant now) {
        return select(tenantId, i -> i.isOverdue(now));
    }

    @Override
    public Flux<ReviewItem> escalated(UUID tenantId) {
        return select(tenantId, ReviewItem::escalated);
    }

    private Flux<ReviewItem> select(UUID tenantId, Predicate<ReviewItem> filter) {
        return Flux.defer(() -> Flux.fromStream(items.values().stream()
                .filter(i -> i.record().tenantId().equals(tenantId))
                .filter(filter)
                .sorted(Comparator.comparing((ReviewItem i) -> i.record().occurredAt()).thenComparing(i -> i.record().id()))));
    }
}
