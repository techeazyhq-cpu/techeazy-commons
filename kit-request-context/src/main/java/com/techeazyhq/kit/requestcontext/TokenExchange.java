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

package com.techeazyhq.kit.requestcontext;

import reactor.core.publisher.Mono;

/**
 * Gets, for a call to an internal service, a token meant for that service and no other. The caller's own token is valid for the service
 * that received it; passed on, it would be valid for every service that accepts the realm's tokens, so any of them could replay it
 * against any other (a confused deputy). Exchanging it for one with the target's audience and only the scopes the call needs, issued by
 * the identity provider and still naming the same person and tenant, means a token that leaks from one hop opens one door.
 */
@FunctionalInterface
public interface TokenExchange {

    /** The narrowed token for {@code target}, on behalf of {@code caller}. An error is never turned into the caller's own token. */
    Mono<String> exchange(RequestContext caller, OutboundTarget target);
}
