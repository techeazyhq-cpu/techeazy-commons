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

import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The token must be meant for this service: its {@code aud} claim, a string or a list, contains the service's own audience. A
 * token without an audience is refused, not accepted: a token for "anyone" would be replayable against every service that trusts
 * the realm.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error MISSING = new OAuth2Error("invalid_token", "The token is not for this service", null);

    private final String audience;

    public AudienceValidator(String audience) {
        this.audience = Objects.requireNonNull(audience, "audience");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> audiences = jwt.getAudience();
        return audiences != null && audiences.contains(audience) ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(MISSING);
    }
}
