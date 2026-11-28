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

import java.util.List;
import java.util.Map;

/**
 * What the dashboard shows and the alert reads (STD-SAGA-04): how many sagas of each type are in each status, the running
 * sagas nobody has moved for too long ({@code stuck}), and the manual-intervention queue. An alert fires when {@link #needsAttention}
 * is true.
 */
public record SagaReport(Map<String, Long> counts, List<SagaInstance> stuck, List<SagaInstance> manualQueue) {

    public boolean needsAttention() {
        return !stuck.isEmpty() || !manualQueue.isEmpty();
    }
}
