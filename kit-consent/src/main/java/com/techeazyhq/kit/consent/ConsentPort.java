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

package com.techeazyhq.kit.consent;

import reactor.core.publisher.Mono;

/**
 * The Consent Port: every service of a product asks this and nothing else about consent, so how consent works is not
 * known anywhere else in the product. There is no consent feature behind it in a product that uses it: the real adapter
 * calls a Consent Manager, a separate system that owns notices, the consent lifecycle and receipts.
 *
 * <p>Rules every adapter keeps (the shared contract test checks them):
 * <ul>
 *   <li>Ids only: purpose codes and opaque references cross the port, never a clinical value or free text.</li>
 *   <li>A check answers {@link ConsentDecision.Allow}, {@link ConsentDecision.Deny} or {@link ConsentDecision.Unavailable},
 *       and only a call that cannot be understood (a null, a malformed value) is an error. A check never completes empty.</li>
 *   <li>{@code UNAVAILABLE} is never read as allow or deny.</li>
 *   <li>Every {@code ALLOW} is audited by the caller with its basis.</li>
 * </ul>
 */
public interface ConsentPort {

    /** May this purpose be processed for this subject right now? */
    Mono<ConsentDecision> check(ConsentCheck check);

    /** Ask the person to consent. */
    Mono<ConsentRequest.Result> request(ConsentRequest request);

    /** A consent changed. Completes when the platform has taken note. */
    Mono<Void> onConsentEvent(ConsentEvent event);
}
