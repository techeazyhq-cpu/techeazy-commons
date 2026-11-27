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

/** A review that cannot be made, with a code the caller can act on (and never any of the record's content). */
public final class ReviewRefused extends RuntimeException {

    /** The accessor tried to review their own break-glass: reviews are made by someone else (four eyes). */
    public static final String SELF_REVIEW = "self-review";

    /** The item was already reviewed; a review is final. */
    public static final String ALREADY_REVIEWED = "already-reviewed";

    /** No such item in this tenant (an item of another tenant looks exactly the same). */
    public static final String NOT_FOUND = "not-found";

    private final String reason;

    public ReviewRefused(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
