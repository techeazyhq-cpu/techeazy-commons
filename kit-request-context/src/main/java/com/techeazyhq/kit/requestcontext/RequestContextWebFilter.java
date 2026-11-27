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

import com.techeazyhq.kit.claims.ClaimNames;
import com.techeazyhq.kit.claims.ClaimsParser;
import com.techeazyhq.kit.consent.PurposeCode;
import com.techeazyhq.kit.consent.web.PurposeOfUseWebFilter;
import com.techeazyhq.kit.web.Problems;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Puts the {@link RequestContext} in the Reactor context of every signed-in request. It runs after Spring Security, so the
 * token has been verified; the claims are read from it with {@link ClaimsParser} and the request's purpose of use (put on the
 * exchange by {@link PurposeOfUseWebFilter}) is added. A call whose claims are not valid (no tenant, or a part that is not well
 * formed) gets <b>no</b> context, and anything that needs one fails loudly: there is no default tenant. An anonymous call, or one
 * authenticated some other way, gets none either.
 *
 * <p>Register it as a bean; it is ordered at 0, after Spring Security's chain and the purpose filter.
 */
public final class RequestContextWebFilter implements WebFilter, Ordered {

    private final ClaimNames names;

    /** With the default claim names. */
    public RequestContextWebFilter() {
        this(ClaimNames.defaults());
    }

    /** With the names this product's identity provider uses for the claims. */
    public RequestContextWebFilter(ClaimNames names) {
        this.names = Objects.requireNonNull(names, "names");
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        PurposeCode purpose = exchange.getAttribute(PurposeOfUseWebFilter.ATTRIBUTE);
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .flatMap(token -> Mono.justOrEmpty(contextOf(token, purpose, Problems.correlationId(exchange))))
                .map(context -> chain.filter(exchange).contextWrite(ctx -> ctx.put(RequestContext.KEY, context)))
                .defaultIfEmpty(chain.filter(exchange))
                .flatMap(next -> next);
    }

    private Optional<RequestContext> contextOf(JwtAuthenticationToken token, PurposeCode purpose, String correlationId) {
        return switch (ClaimsParser.parse(token.getToken().getClaims(), names)) {
            case ClaimsParser.Valid valid -> Optional.of(new RequestContext(correlationId, token.getToken().getTokenValue(),
                    purpose == null ? valid.claims() : valid.claims().withPurpose(purpose.value())));
            case ClaimsParser.Invalid invalid -> Optional.empty();
        };
    }
}
