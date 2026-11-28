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

/**
 * Another change to the same aggregate got that version first (two commands raced). The caller reloads the aggregate and
 * retries, or answers a conflict: it must not overwrite.
 */
public final class VersionConflictException extends RuntimeException {

    public VersionConflictException(AggregateKey key, long version) {
        super("aggregate " + key.aggregateType() + "/" + key.aggregateId() + " already has version " + version);
    }
}
