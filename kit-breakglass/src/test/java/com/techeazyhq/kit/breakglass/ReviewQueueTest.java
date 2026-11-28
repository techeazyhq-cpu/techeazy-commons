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

import static com.techeazyhq.kit.breakglass.BreakGlassRecordTest.AT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** "Break-glass logged and queued for review" (EP-09-07): every use is queued, due in 24 hours, and reviewed by someone else. */
class ReviewQueueTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();

    private final Clock clock = Clock.fixed(AT.plus(Duration.ofHours(2)), ZoneOffset.UTC);
    private final InMemoryReviewQueue queue = new InMemoryReviewQueue(clock);

    private static BreakGlassRecord at(UUID tenant, UUID accessor, Instant when) {
        return new BreakGlassRecord(UUID.randomUUID(), tenant, UUID.randomUUID(), accessor, "subject-77", "account", "read",
                "locked_out_urgent", "Caller could not be reached and the transfer would have failed", when, "corr-1");
    }

    @Test
    void everyUseIsQueuedPendingAndDueWithinTheSla() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();

        assertTrue(item.isPending());
        assertEquals(AT.plus(Duration.ofHours(24)), item.dueAt());
        assertEquals(List.of(item), queue.pending(TENANT).collectList().block());
        assertFalse(item.escalated());
    }

    @Test
    void enqueuingTheSameRecordTwiceGivesTheOneItem() {
        BreakGlassRecord r = at(TENANT, DOCTOR, AT);

        ReviewItem first = queue.enqueue(r).block();
        ReviewItem second = queue.enqueue(r).block();

        assertEquals(first, second);
        assertEquals(1, queue.pending(TENANT).count().block());
    }

    @Test
    void anotherPersonReviewsAndTheItemLeavesThePendingList() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();

        ReviewItem done = queue.review(TENANT, item.record().id(), REVIEWER, ReviewItem.Outcome.JUSTIFIED, "Documented, fine").block();

        assertEquals(ReviewItem.Status.REVIEWED, done.status());
        assertEquals(REVIEWER, done.reviewerUserId());
        assertEquals(clock.instant(), done.reviewedAt());
        assertEquals(ReviewItem.Outcome.JUSTIFIED, done.outcome());
        assertFalse(done.escalated());
        assertEquals(0, queue.pending(TENANT).count().block());
        assertEquals(done, queue.find(TENANT, item.record().id()).block());
    }

    @Test
    void aBreakGlassIsNeverReviewedByItsOwnAccessor() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();

        ReviewRefused refused = assertThrows(ReviewRefused.class,
                () -> queue.review(TENANT, item.record().id(), DOCTOR, ReviewItem.Outcome.JUSTIFIED, null).block());

        assertEquals(ReviewRefused.SELF_REVIEW, refused.reason());
        assertTrue(queue.find(TENANT, item.record().id()).block().isPending(), "still waiting");
    }

    @Test
    void aReviewIsFinal() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();
        queue.review(TENANT, item.record().id(), REVIEWER, ReviewItem.Outcome.NOT_JUSTIFIED, "No emergency").block();

        ReviewRefused refused = assertThrows(ReviewRefused.class,
                () -> queue.review(TENANT, item.record().id(), UUID.randomUUID(), ReviewItem.Outcome.JUSTIFIED, "Changed my mind").block());

        assertEquals(ReviewRefused.ALREADY_REVIEWED, refused.reason());
        assertEquals(ReviewItem.Outcome.NOT_JUSTIFIED, queue.find(TENANT, item.record().id()).block().outcome());
    }

    @Test
    void aBreakGlassThatIsNotJustifiedIsEscalated() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();

        ReviewItem done = queue.review(TENANT, item.record().id(), REVIEWER, ReviewItem.Outcome.NOT_JUSTIFIED, "Not the agents custient").block();

        assertTrue(done.escalated());
        assertEquals(List.of(done), queue.escalated(TENANT).collectList().block());
    }

    @Test
    void aJustifiedBreakGlassIsNotEscalated() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();
        queue.review(TENANT, item.record().id(), REVIEWER, ReviewItem.Outcome.JUSTIFIED, null).block();

        assertEquals(0, queue.escalated(TENANT).count().block());
    }

    @Test
    void anItemIsOverdueOnlyWhenPendingAndPastItsDeadline() {
        ReviewItem old = queue.enqueue(at(TENANT, DOCTOR, AT.minus(Duration.ofHours(30)))).block();   // due 6 hours ago
        ReviewItem fresh = queue.enqueue(at(TENANT, DOCTOR, AT)).block();                              // due tomorrow
        ReviewItem reviewedLate = queue.enqueue(at(TENANT, DOCTOR, AT.minus(Duration.ofHours(40)))).block();
        queue.review(TENANT, reviewedLate.record().id(), REVIEWER, ReviewItem.Outcome.JUSTIFIED, null).block();

        List<ReviewItem> overdue = queue.overdue(TENANT, clock.instant()).collectList().block();

        assertEquals(List.of(old), overdue);
        assertFalse(fresh.isOverdue(clock.instant()));
        assertFalse(queue.find(TENANT, reviewedLate.record().id()).block().isOverdue(clock.instant()));
    }

    @Test
    void theDeadlineItselfIsNotYetOverdue() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();

        assertFalse(item.isOverdue(item.dueAt()));
        assertTrue(item.isOverdue(item.dueAt().plusMillis(1)));
    }

    @Test
    void theQueuesAreListedOldestFirst() {
        ReviewItem newest = queue.enqueue(at(TENANT, DOCTOR, AT)).block();
        ReviewItem oldest = queue.enqueue(at(TENANT, DOCTOR, AT.minus(Duration.ofHours(5)))).block();
        ReviewItem middle = queue.enqueue(at(TENANT, DOCTOR, AT.minus(Duration.ofHours(2)))).block();

        assertEquals(List.of(oldest, middle, newest), queue.pending(TENANT).collectList().block());
    }

    @Test
    void anotherTenantNeverSeesOrReviewsAnItem() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT.minus(Duration.ofHours(30)))).block();

        assertEquals(0, queue.pending(OTHER_TENANT).count().block());
        assertEquals(0, queue.overdue(OTHER_TENANT, clock.instant()).count().block());
        assertNull(queue.find(OTHER_TENANT, item.record().id()).block());
        ReviewRefused refused = assertThrows(ReviewRefused.class,
                () -> queue.review(OTHER_TENANT, item.record().id(), REVIEWER, ReviewItem.Outcome.JUSTIFIED, null).block());
        assertEquals(ReviewRefused.NOT_FOUND, refused.reason(), "looks exactly like an item that does not exist");
        assertTrue(queue.find(TENANT, item.record().id()).block().isPending());
    }

    @Test
    void anItemThatDoesNotExistCannotBeReviewed() {
        assertEquals(ReviewRefused.NOT_FOUND, assertThrows(ReviewRefused.class,
                () -> queue.review(TENANT, UUID.randomUUID(), REVIEWER, ReviewItem.Outcome.JUSTIFIED, null).block()).reason());
    }

    @Test
    void twoReviewersAtOnceGiveOneReviewAndOneRefusal() throws Exception {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    queue.review(TENANT, item.record().id(), UUID.randomUUID(), ReviewItem.Outcome.JUSTIFIED, null).block();
                    accepted.incrementAndGet();
                } catch (ReviewRefused e) {
                    refused.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(1, accepted.get());
        assertEquals(7, refused.get());
    }

    @Test
    void theItemNeverPrintsTheJustificationOrTheNote() {
        ReviewItem item = queue.enqueue(at(TENANT, DOCTOR, AT)).block();
        ReviewItem done = queue.review(TENANT, item.record().id(), REVIEWER, ReviewItem.Outcome.NOT_JUSTIFIED,
                "customer has a loan and was not in this agent's branch").block();

        assertFalse(done.toString().contains("diabetes") || done.toString().contains("Unconscious"));
    }

    @Test
    void anItemIsConsistentWithItsStatus() {
        ReviewItem pending = ReviewItem.pending(at(TENANT, DOCTOR, AT));

        assertThrows(IllegalArgumentException.class, () -> new ReviewItem(pending.record(), pending.dueAt(), ReviewItem.Status.PENDING,
                REVIEWER, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new ReviewItem(pending.record(), pending.dueAt(), ReviewItem.Status.REVIEWED,
                null, null, null, null, false));
    }
}
