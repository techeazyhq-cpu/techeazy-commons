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

import java.util.Map;
import java.util.UUID;

/**
 * What a step gets when it runs. {@code idempotencyKey} is the same for every attempt of this step in this direction, and
 * different for the action and its compensation: send it to the other service with the command so a retry (after a timeout, a
 * crash, a lost answer) does the work once.
 */
public record StepContext(UUID sagaId, UUID tenantId, String correlationId, String step, SagaPhase phase, int attempt,
                          String idempotencyKey, Map<String, String> data) {
}
