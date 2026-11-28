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

import com.techeazyhq.kit.claims.Claims;
import com.techeazyhq.kit.consent.PurposeCode;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Who is calling, for the whole life of one request, carried in the Reactor context so that it follows the request across
 * operators, scheduler hops, streaming responses and outbound calls without being passed as a parameter or kept in a
 * thread-local (which a reactive request would lose, or worse, leak to another request).
 *
 * <p>{@code claims} is the caller as the verified token states it (person, tenant, roles and the product's
 * attributes) with the request's purpose of use added. A service never takes any of it from a header or a body: a downstream
 * service is sent the same token and reads the same claims from it. The tenant is never a default, and never a header.
 *
 * <p>The bearer token travels with the context only so that a gateway can relay it to internal hosts (see
 * {@link OutboundHttp#relay}); {@link #toString()} never prints it.
 *
 * @param correlationId the id of this call across services; may be null when no correlation filter ran
 * @param bearerToken   the verified token as it came in
 * @param claims        the caller's claims, with the purpose when the request stated one
 */
public record RequestContext(String correlationId, String bearerToken, Claims claims) {

    /** The key in the Reactor context. */
    public static final String KEY = "techeazy.requestContext";

    public RequestContext {
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(claims, "claims");
    }

    /** The caller's tenant. */
    public UUID tenantId() {
        return claims.tenantId();
    }

    /** The purpose of use of this request as a code, or null on a path that is not protected. */
    public String purpose() {
        return claims.purpose();
    }

    /** The same, as a {@link PurposeCode} for the Consent Port; null when there is none. */
    public PurposeCode purposeCode() {
        return claims.purpose() == null ? null : PurposeCode.of(claims.purpose());
    }

    /** The context of the request this subscription belongs to. Fails loudly if there is none: no default tenant, ever. */
    public static Mono<RequestContext> current() {
        return Mono.deferContextual(ctx -> ctx.<RequestContext>getOrEmpty(KEY)
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new IllegalStateException("No request context: the call is not signed in"))));
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "RequestContext[correlationId=" + correlationId + ", " + claims + "]";
    }
}
