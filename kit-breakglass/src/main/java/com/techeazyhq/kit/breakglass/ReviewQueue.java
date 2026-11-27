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

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Where break-glasses wait to be reviewed. Every use of break-glass is enqueued when it happens and is not done until
 * someone else has reviewed it. Every operation is scoped to a tenant: another tenant's items do not exist as far as a
 * caller is concerned. A database adapter implements this later; {@link InMemoryReviewQueue} is the reference.
 */
public interface ReviewQueue {

    /** Queue a break-glass for review, due within the SLA. Enqueuing the same record twice gives the one item. */
    Mono<ReviewItem> enqueue(BreakGlassRecord record);

    /**
     * Record a review. The reviewer must not be the accessor ({@link ReviewRefused#SELF_REVIEW}); a review is final
     * ({@link ReviewRefused#ALREADY_REVIEWED}); an item not in this tenant is {@link ReviewRefused#NOT_FOUND}. A
     * {@code NOT_JUSTIFIED} outcome marks the item escalated.
     */
    Mono<ReviewItem> review(UUID tenantId, UUID itemId, UUID reviewerUserId, ReviewItem.Outcome outcome, String reviewerNote);

    Mono<ReviewItem> find(UUID tenantId, UUID itemId);

    /** Everything not yet reviewed, oldest first. */
    Flux<ReviewItem> pending(UUID tenantId);

    /** Pending items whose deadline has passed at {@code now}, oldest first: what the alerts and AD-05 act on. */
    Flux<ReviewItem> overdue(UUID tenantId, Instant now);

    /** Reviewed items that were not justified, oldest first: what the security officer follows up. */
    Flux<ReviewItem> escalated(UUID tenantId);
}
