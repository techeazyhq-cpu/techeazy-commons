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
import java.util.Objects;
import java.util.UUID;

/**
 * A break-glass waiting for, or past, its review. Immutable: a review produces a new item. {@code reviewerNote} is free text
 * and may be health data (D4), so, like the record's justification, it never appears in {@link #toString()}.
 */
public record ReviewItem(BreakGlassRecord record, Instant dueAt, Status status, UUID reviewerUserId, Instant reviewedAt,
                         Outcome outcome, String reviewerNote, boolean escalated) {

    public enum Status { PENDING, REVIEWED }

    /** {@code NOT_JUSTIFIED} escalates: to the tenant's security officer, and into the access-abuse detector (AD-05). */
    public enum Outcome { JUSTIFIED, NOT_JUSTIFIED }

    public ReviewItem {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(status, "status");
        if (status == Status.PENDING && (reviewerUserId != null || reviewedAt != null || outcome != null || escalated)) {
            throw new IllegalArgumentException("A pending item has no review");
        }
        if (status == Status.REVIEWED && (reviewerUserId == null || reviewedAt == null || outcome == null)) {
            throw new IllegalArgumentException("A reviewed item says who, when and the outcome");
        }
    }

    /** A new, unreviewed item, due {@link BreakGlassRecord#REVIEW_SLA} after the access. */
    public static ReviewItem pending(BreakGlassRecord record) {
        return new ReviewItem(record, record.reviewDueAt(), Status.PENDING, null, null, null, null, false);
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    /** Pending, and the deadline has passed. A reviewed item is never overdue. */
    public boolean isOverdue(Instant now) {
        return isPending() && now.isAfter(dueAt);
    }

    ReviewItem reviewed(UUID reviewer, Outcome result, String note, Instant when) {
        return new ReviewItem(record, dueAt, Status.REVIEWED, reviewer, when, result, note, result == Outcome.NOT_JUSTIFIED);
    }

    @Override
    public String toString() {
        return "ReviewItem[record=" + record + ", status=" + status + ", dueAt=" + dueAt + ", outcome=" + outcome
                + ", escalated=" + escalated + "]";
    }
}
