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

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * What a call site calls. {@code require(site, tenant, subject, purpose)} asks the Consent Port through the fail-closed
 * {@link ConsentEnforcer}, with the site's own timeout, and either lets the handler continue or stops it:
 * <ul>
 *   <li>{@link ConsentOutcome.Proceed}: the mono completes with it; the caller audits the {@code basis};</li>
 *   <li>{@link ConsentOutcome.Refused}: errors with {@link ConsentRefusedException} (a web layer answers 403);</li>
 *   <li>{@link ConsentOutcome.TryLater}: errors with {@link ConsentUnavailableException} (503 with Retry-After).</li>
 * </ul>
 * A site may only be used for its own purposes ({@link ConsentCallSite#accepts}); asking about another is a programming error
 * and fails, so a site cannot quietly ask the wrong question. Nothing is cached.
 */
public final class ConsentGuard {

    private final ConsentPort port;
    private final Function<ConsentCallSite, Duration> timeouts;
    private final Map<ConsentCallSite, ConsentEnforcer> enforcers = new ConcurrentHashMap<>();

    /** Each site waits for its own {@link ConsentCallSite#timeout()}. */
    public ConsentGuard(ConsentPort port) {
        this(port, ConsentCallSite::timeout);
    }

    /** As above with other timeouts, for a test or an environment that must differ. */
    public ConsentGuard(ConsentPort port, Function<ConsentCallSite, Duration> timeouts) {
        this.port = Objects.requireNonNull(port, "port");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    }

    /**
     * @param subjectRef an opaque reference to the person whose data is processed, never a name or a value
     * @return the {@code Proceed} outcome, or an error as above
     */
    public Mono<ConsentOutcome.Proceed> require(ConsentCallSite site, UUID tenantId, String subjectRef, PurposeCode purpose) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(purpose, "purpose");
        if (!site.accepts(purpose)) {
            return Mono.error(new IllegalArgumentException(site + " is not for the purpose " + purpose));
        }
        ConsentCheck check;
        try {
            check = new ConsentCheck(tenantId, subjectRef, purpose);
        } catch (RuntimeException e) {
            return Mono.error(e);
        }
        ConsentEnforcer enforcer = enforcers.computeIfAbsent(site, s -> new ConsentEnforcer(port, timeouts.apply(s)));
        return enforcer.enforce(check).flatMap(outcome -> {
            if (outcome instanceof ConsentOutcome.Proceed proceed) {
                return Mono.just(proceed);
            }
            if (outcome instanceof ConsentOutcome.Refused refused) {
                return Mono.<ConsentOutcome.Proceed>error(new ConsentRefusedException(refused.reason()));
            }
            return Mono.<ConsentOutcome.Proceed>error(new ConsentUnavailableException(((ConsentOutcome.TryLater) outcome).retryAfter()));
        });
    }
}
