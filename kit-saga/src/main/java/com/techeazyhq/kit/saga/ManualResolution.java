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

/** What the owning team decides for a saga in the manual queue. */
public enum ManualResolution {
    /** The cause is fixed: try the step that failed again, from the first attempt. */
    RETRY,
    /** The work was undone by hand: close the saga as compensated. */
    MARK_COMPENSATED,
    /** For a forward-only saga: the work was done by hand, close the saga as completed. */
    MARK_COMPLETED
}
