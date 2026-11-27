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

package com.techeazyhq.kit.access;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * The PEP. A service builds the policy input from the verified request context and calls {@link #enforce}; the result is a
 * {@link Decision} that was an allow and whose obligations are done, or an error that stops the request.
 *
 * <ul>
 *   <li><b>Deny by default.</b> Only {@code allow == true} proceeds.</li>
 *   <li><b>No answer is never an allow.</b> An error, an empty answer, an answer not understood, a timeout, and an
 *       {@code undetermined} decision are all {@link AccessUnavailableException}. There is no cache and no fallback.</li>
 *   <li><b>An obligation nobody can meet is a no.</b> An allow that names an obligation with no handler is
 *       {@link AccessDeniedException}: the PEP never lets a request through that the policy said needs something it cannot do.
 *       Obligation handlers run before the caller proceeds; one that fails makes the access unavailable.</li>
 * </ul>
 * A decision with {@code allow} and {@code undetermined} both false is a deny.
 */
public final class PolicyEnforcementPoint {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);
    public static final Duration DEFAULT_RETRY = Duration.ofSeconds(5);

    private static final System.Logger LOG = System.getLogger(PolicyEnforcementPoint.class.getName());

    private final PolicyDecisionPoint pdp;
    private final Duration timeout;
    private final Map<String, ObligationHandler> handlers;

    public PolicyEnforcementPoint(PolicyDecisionPoint pdp, Duration timeout, List<ObligationHandler> handlers) {
        this.pdp = Objects.requireNonNull(pdp, "pdp");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(ObligationHandler::key, Function.identity()));
    }

    public PolicyEnforcementPoint(PolicyDecisionPoint pdp, List<ObligationHandler> handlers) {
        this(pdp, DEFAULT_TIMEOUT, handlers);
    }

    /** The decision, if and only if it is an allow and its obligations are fulfilled. */
    public Mono<Decision> enforce(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        return Mono.defer(() -> pdp.decide(input))
                .timeout(timeout)
                .onErrorMap(e -> !(e instanceof AccessDeniedException) && !(e instanceof AccessUnavailableException),
                        e -> unavailable(e.getClass().getSimpleName()))
                .switchIfEmpty(Mono.error(() -> unavailable("no answer")))
                .flatMap(decision -> apply(decision, input));
    }

    private Mono<Decision> apply(Decision decision, Map<String, Object> input) {
        if (decision.undetermined()) {
            return Mono.error(unavailable("undetermined"));
        }
        if (!decision.allow()) {
            LOG.log(System.Logger.Level.INFO, "access denied: {0}", decision.reasons());
            return Mono.error(new AccessDeniedException(decision.reasons()));
        }
        Set<String> owed = decision.obligations().entrySet().stream().filter(e -> required(e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(java.util.TreeSet::new));
        Set<String> unknown = owed.stream().filter(k -> !handlers.containsKey(k)).collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!unknown.isEmpty()) {
            LOG.log(System.Logger.Level.ERROR, "allow carries obligations with no handler, refused: {0}", unknown);
            return Mono.error(new AccessDeniedException(List.of("obligation-unsupported")));
        }
        return Mono.whenDelayError(owed.stream().map(k -> handlers.get(k).fulfil(decision, input)).toList())
                .onErrorMap(e -> unavailable("obligation-failed"))
                .thenReturn(decision);
    }

    /** An obligation is owed unless it is plainly switched off. */
    private static boolean required(Object value) {
        return value != null && !Boolean.FALSE.equals(value);
    }

    private static AccessUnavailableException unavailable(String why) {
        LOG.log(System.Logger.Level.WARNING, "access decision unavailable: {0}", why);
        return new AccessUnavailableException(why, DEFAULT_RETRY);
    }
}
