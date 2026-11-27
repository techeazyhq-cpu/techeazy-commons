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
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * The fail-closed rules in one place, so no call site has to remember them. Every caller asks through this and
 * gets a {@link ConsentOutcome}.
 *
 * <ul>
 *   <li><b>Only an {@code ALLOW} proceeds.</b> {@code DENY} is {@link ConsentOutcome.Refused}.</li>
 *   <li><b>{@code UNAVAILABLE} is neither allow nor deny</b>: it is {@link ConsentOutcome.TryLater}.</li>
 *   <li><b>Any failure of the adapter is {@code UNAVAILABLE}</b>: an exception, an error signal, an empty answer, a
 *       {@code null}, a call that does not answer within the timeout. Nothing that goes wrong can become an allow.</li>
 *   <li><b>No fallback and no grace.</b> The enforcer keeps no cache and assumes nothing. This holds for every purpose, and
 *       so for health data and children's purposes, which the design says fail closed (the Consent Manager's local
 *       sidecar may allow a bounded offline grace for other purposes, never for these; here there is none at all, and any
 *       grace added later must not apply to them).</li>
 *   <li><b>The retry interval is bounded</b>, so an adapter cannot ask a caller to wait forever or to hammer it.</li>
 * </ul>
 *
 * <p>It never calls {@link ConsentPort#request}: asking the person again is for a {@code Refused} outcome only (see
 * {@link ConsentOutcome#mayAskPersonForConsent()}).
 */
public final class ConsentEnforcer {

    /** How long to wait for the adapter before giving up and saying try later. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    /** The retry interval when the adapter failed without saying when to try again. */
    public static final Duration DEFAULT_RETRY = Duration.ofSeconds(30);

    static final Duration MIN_RETRY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY = Duration.ofMinutes(15);

    private static final System.Logger LOG = System.getLogger(ConsentEnforcer.class.getName());

    private final ConsentPort port;
    private final Duration timeout;

    public ConsentEnforcer(ConsentPort port) {
        this(port, DEFAULT_TIMEOUT);
    }

    public ConsentEnforcer(ConsentPort port, Duration timeout) {
        this.port = Objects.requireNonNull(port, "port");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    /** Never errors and never completes empty: every outcome is one of the three. A malformed call is the caller's bug and is thrown when the {@link ConsentCheck} is built, not here. */
    public Mono<ConsentOutcome> enforce(ConsentCheck check) {
        Objects.requireNonNull(check, "check");
        return Mono.defer(() -> {
                    Mono<ConsentDecision> answer = port.check(check);
                    return answer == null ? Mono.<ConsentDecision>empty() : answer;
                })
                .timeout(timeout)
                .map(ConsentEnforcer::outcomeOf)
                .onErrorResume(Exception.class, e -> {
                    // log the kind of failure, never the check: it carries a subject reference
                    LOG.log(System.Logger.Level.WARNING, "consent check unavailable ({0}): failing closed", e.getClass().getSimpleName());
                    return Mono.just(tryLater(DEFAULT_RETRY));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    LOG.log(System.Logger.Level.WARNING, "consent check gave no answer: failing closed");
                    return tryLater(DEFAULT_RETRY);
                }));
    }

    private static ConsentOutcome outcomeOf(ConsentDecision decision) {
        if (decision instanceof ConsentDecision.Allow allow) {
            return new ConsentOutcome.Proceed(allow.basis());
        }
        if (decision instanceof ConsentDecision.Deny deny) {
            return new ConsentOutcome.Refused(deny.reason());
        }
        if (decision instanceof ConsentDecision.Unavailable unavailable) {
            return tryLater(unavailable.retryAfter());
        }
        return tryLater(DEFAULT_RETRY); // not reachable for a sealed type, and if it ever is, it is not an allow
    }

    private static ConsentOutcome tryLater(Duration requested) {
        Duration bounded = requested.compareTo(MIN_RETRY) < 0 ? MIN_RETRY
                : requested.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : requested;
        return new ConsentOutcome.TryLater(bounded);
    }
}
