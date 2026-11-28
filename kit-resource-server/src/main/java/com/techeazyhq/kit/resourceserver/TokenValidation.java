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

package com.techeazyhq.kit.resourceserver;

import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * What makes a bearer token acceptable to a service: a signature that verifies against the identity provider's keys (RS256 only: a
 * token signed with {@code none} or a symmetric algorithm is refused), a lifetime that has not ended (with 30 seconds of clock
 * skew, no more), an {@code iss} that is exactly the configured issuer, and an {@code aud} that contains the service's own
 * audience. A token that fails any of them is a 401.
 *
 * <p>{@link #require} is called at start-up: a service not told its issuer and its audience does not start, so a deployment can
 * never run with the check missing (an empty setting was the default before).
 */
public final class TokenValidation {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private TokenValidation() {
    }

    /** Refuses to start unless the issuer and the audience are set and look right. */
    public static void require(String issuer, String audience) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("The token issuer is not set (ogh.security.issuer-uri / OGH_ISSUER_URI): "
                    + "a service that does not know who issues its tokens does not start");
        }
        URI uri = URI.create(issuer.strip());
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && LOOPBACK.contains(uri.getHost().toLowerCase());
        if (!https && !loopback) {
            throw new IllegalStateException("The token issuer must be an https address, or http on the loopback for a developer's machine");
        }
        if (audience == null || !audience.matches("[A-Za-z0-9:._-]{1,128}")) {
            throw new IllegalStateException("The token audience is not set (ogh.security.audience / OGH_AUDIENCE) or is not a plain name: "
                    + "a service that does not know whom its tokens are for does not start");
        }
    }

    /** The checks on a verified token: time, issuer (exact) and audience. */
    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        require(issuer, audience);
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(CLOCK_SKEW), JwtValidators.createDefaultWithIssuer(issuer.strip()),
                new AudienceValidator(audience));
    }

    /** The decoder of a service: keys fetched from the identity provider on the first token, then the checks above. */
    public static ReactiveJwtDecoder decoder(String jwkSetUri, String issuer, String audience) {
        require(issuer, audience);
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(validator(issuer, audience));
        return decoder;
    }

    /** As {@link #decoder(String, String, String)} for one known key, for tests. */
    public static ReactiveJwtDecoder decoder(RSAPublicKey key, String issuer, String audience) {
        require(issuer, audience);
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(key).build();
        decoder.setJwtValidator(validator(issuer, audience));
        return decoder;
    }
}
