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

package com.techeazyhq.kit.saga;

/** Where a saga is in its life. */
public enum SagaStatus {
    /** Moving forward or compensating. */
    RUNNING,
    /** Every step is done. */
    COMPLETED,
    /** A step failed and every completed step was undone. */
    COMPENSATED,
    /** Something could not be undone automatically: a person with the owning team's role decides (the manual queue). */
    NEEDS_INTERVENTION;

    public boolean isFinished() {
        return this != RUNNING;
    }
}
