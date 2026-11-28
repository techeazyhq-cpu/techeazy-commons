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

import reactor.core.publisher.Mono;

/**
 * Hands an event to the broker (Pulsar). Delivery is <b>at least once</b>: a crash between publishing and moving the cursor
 * publishes again, so a consumer must treat the event's {@code id} as an idempotency key (the projection runner does).
 */
public interface EventPublisher {

    Mono<Void> publish(EventRecord event);
}
