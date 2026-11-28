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

package com.techeazyhq.kit.saga;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs sagas. The rules (STD-SAGA-01 to 05):
 *
 * <ul>
 *   <li><b>Persisted.</b> A saga is stored before its first step and after every change. A worker that dies is replaced by the
 *       next run: the step it had in flight is retried when its deadline passes.</li>
 *   <li><b>Idempotent.</b> Every attempt of a step carries the same {@link StepContext#idempotencyKey()}; the action and its
 *       compensation have different keys. The other service does the work once however often it is asked.</li>
 *   <li><b>Timeouts and retries.</b> A step that throws or does not answer within its timeout is retried after its backoff, up to
 *       its limit; a step that answers {@link StepOutcome.Failed} is not retried.</li>
 *   <li><b>Compensation in reverse.</b> When a step cannot succeed, the steps already done are undone, last first. A step whose
 *       outcome is unknown (it timed out) is compensated too, as well: compensations tolerate "nothing to undo".</li>
 *   <li><b>Manual queue.</b> A compensation that cannot be completed after its retries moves the saga to
 *       {@link SagaStatus#NEEDS_INTERVENTION}, owned by the definition's team, until {@link #resolve}.</li>
 *   <li><b>No transaction across a call.</b> The store is used in single statements; the call to the other service happens with
 *       no transaction open.</li>
 * </ul>
 * Only one worker moves a saga at a time: every change is a compare-and-set on the saga's version.
 */
public final class SagaEngine {

    private static final System.Logger LOG = System.getLogger(SagaEngine.class.getName());
    private static final int WORKERS = 8;

    private final Map<String, SagaDefinition> definitions = new LinkedHashMap<>();
    private final SagaStore store;
    private final Clock clock;
    private final SagaListener listener;

    public SagaEngine(SagaStore store, Clock clock, SagaListener listener, List<SagaDefinition> definitions) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
        for (SagaDefinition definition : definitions) {
            if (this.definitions.put(definition.type(), definition) != null) {
                throw new IllegalArgumentException("two sagas are called " + definition.type());
            }
        }
    }

    // ---- starting ---------------------------------------------------------------------------------------------------------

    /** Stores a new saga, due at once. It runs when the engine next runs ({@link #runDue}), or call {@link #advance}. */
    public Mono<SagaInstance> start(String type, UUID tenantId, String correlationId, Map<String, String> data) {
        SagaDefinition definition = definitions.get(type);
        if (definition == null) {
            return Mono.error(new IllegalArgumentException("unknown saga type " + type));
        }
        Instant now = clock.instant();
        SagaInstance saga = new SagaInstance(UUID.randomUUID(), tenantId, type, correlationId, SagaStatus.RUNNING,
                SagaPhase.FORWARD, 0, 0, false, now, data, null, definition.owner(), 0, now, now);
        return store.insert(saga).thenReturn(saga).doOnSuccess(this::notifyListener);
    }

    // ---- running ----------------------------------------------------------------------------------------------------------

    /** Moves every saga that is due one step. A failure of one saga never stops the others. Returns how many it looked at. */
    public Mono<Long> runDue(int limit) {
        return store.due(clock.instant(), limit)
                .flatMap(saga -> advance(saga.id()).onErrorResume(failure -> {
                    LOG.log(System.Logger.Level.WARNING, "saga {0} could not be advanced: {1}", saga.id(),
                            failure.getClass().getSimpleName());
                    return Mono.empty();
                }), WORKERS)
                .count();
    }

    /** Runs {@link #runDue} every {@code interval} until disposed. */
    public Disposable schedule(Duration interval, int limit) {
        return Flux.interval(interval, Schedulers.parallel()).onBackpressureDrop()
                .concatMap(tick -> runDue(limit).onErrorResume(failure -> Mono.empty())).subscribe();
    }

    /** Does the next piece of work of one saga, if it is due. Empty when it is not due, finished, or another worker took it. */
    public Mono<SagaInstance> advance(UUID id) {
        return store.find(id).flatMap(saga -> {
            Instant now = clock.instant();
            if (saga.status() != SagaStatus.RUNNING || saga.nextAttemptAt().isAfter(now)) {
                return Mono.empty();
            }
            SagaDefinition definition = definitions.get(saga.type());
            if (definition == null) {
                return Mono.error(new IllegalStateException("no definition for saga type " + saga.type()));
            }
            Step step = definition.steps().get(saga.stepIndex());
            if (saga.inFlight()) {
                // the step was started and never reported back: its deadline has passed
                return failedAttempt(saga, definition, step, "step-timeout");
            }
            if (saga.phase() == SagaPhase.COMPENSATING && !step.hasCompensation()) {
                return moveOnFromCompensation(saga, definition, now);
            }
            return invoke(saga, definition, step, now);
        });
    }

    private Mono<SagaInstance> invoke(SagaInstance saga, SagaDefinition definition, Step step, Instant now) {
        SagaInstance claimed = saga.change(now, d -> {
            d.attempt = saga.attempt() + 1;
            d.inFlight = true;
            d.nextAttemptAt = now.plus(step.timeout());
        });
        return cas(saga, claimed).flatMap(won -> {
            if (!won) {
                return Mono.<SagaInstance>empty();
            }
            StepContext context = new StepContext(saga.id(), saga.tenantId(), saga.correlationId(), step.name(), saga.phase(),
                    claimed.attempt(), idempotencyKey(saga.id(), step.name(), saga.phase()), saga.data());
            var call = saga.phase() == SagaPhase.FORWARD ? step.action() : step.compensation();
            return Mono.defer(() -> call.apply(context))
                    .timeout(step.timeout())
                    .map(outcome -> (Object) outcome)
                    .defaultIfEmpty(new Attempt("step-no-answer"))
                    .onErrorResume(failure -> Mono.just((Object) new Attempt(
                            failure instanceof TimeoutException ? "step-timeout" : "step-error")))
                    .flatMap(result -> settle(claimed, definition, step, result));
        });
    }

    /** An attempt that threw, timed out or gave no answer: not an outcome the step chose. */
    private record Attempt(String code) {
    }

    private Mono<SagaInstance> settle(SagaInstance claimed, SagaDefinition definition, Step step, Object result) {
        Instant now = clock.instant();
        if (result instanceof StepOutcome.Done done) {
            return completeStep(claimed, definition, done.data(), now);
        }
        if (result instanceof StepOutcome.Waiting) {
            return Mono.just(claimed); // stays in flight; the deadline already set is the signal's deadline
        }
        if (result instanceof StepOutcome.Failed failed) {
            return definitiveFailure(claimed, definition, failed.code(), now);
        }
        return failedAttempt(claimed, definition, step, ((Attempt) result).code());
    }

    // ---- outcomes ---------------------------------------------------------------------------------------------------------

    private Mono<SagaInstance> completeStep(SagaInstance saga, SagaDefinition definition, Map<String, String> produced, Instant now) {
        Map<String, String> merged = new LinkedHashMap<>(saga.data());
        merged.putAll(produced);
        if (saga.phase() == SagaPhase.FORWARD) {
            boolean last = saga.stepIndex() + 1 >= definition.steps().size();
            return persist(saga, saga.change(now, d -> {
                d.data = SagaInstance.checked(merged);
                d.attempt = 0;
                d.inFlight = false;
                d.nextAttemptAt = now;
                d.lastError = null;
                if (last) {
                    d.status = SagaStatus.COMPLETED;
                } else {
                    d.stepIndex = saga.stepIndex() + 1;
                }
            }));
        }
        return persist(saga, nextCompensation(saga, now, SagaInstance.checked(merged)));
    }

    /** Compensation of the current step is done (or there is none): the one before it, or finished. */
    private SagaInstance nextCompensation(SagaInstance saga, Instant now, Map<String, String> data) {
        return saga.change(now, d -> {
            d.data = data;
            d.attempt = 0;
            d.inFlight = false;
            d.nextAttemptAt = now;
            if (saga.stepIndex() == 0) {
                d.status = SagaStatus.COMPENSATED;
            } else {
                d.stepIndex = saga.stepIndex() - 1;
            }
        });
    }

    private Mono<SagaInstance> moveOnFromCompensation(SagaInstance saga, SagaDefinition definition, Instant now) {
        return persist(saga, nextCompensation(saga, now, saga.data()));
    }

    /** The step cannot succeed whatever is tried: undo what was done before it. */
    private Mono<SagaInstance> definitiveFailure(SagaInstance saga, SagaDefinition definition, String code, Instant now) {
        if (saga.phase() == SagaPhase.COMPENSATING) {
            return escalate(saga, definition, code, now);
        }
        return startCompensation(saga, definition, code, saga.stepIndex() - 1, now);
    }

    /** An attempt that threw, timed out or never answered: try again, or give up. */
    private Mono<SagaInstance> failedAttempt(SagaInstance saga, SagaDefinition definition, Step step, String code) {
        Instant now = clock.instant();
        if (saga.attempt() < step.maxAttempts()) {
            Duration wait = step.backoff().multipliedBy(Math.max(1, saga.attempt()));
            return persist(saga, saga.change(now, d -> {
                d.inFlight = false;
                d.nextAttemptAt = now.plus(wait);
                d.lastError = code;
            }));
        }
        if (saga.phase() == SagaPhase.COMPENSATING) {
            return escalate(saga, definition, code, now);
        }
        // out of attempts: the step may have taken effect without our knowing, so its own compensation runs too
        return startCompensation(saga, definition, code, saga.stepIndex(), now);
    }

    private Mono<SagaInstance> startCompensation(SagaInstance saga, SagaDefinition definition, String code, int from, Instant now) {
        return persist(saga, saga.change(now, d -> {
            d.phase = SagaPhase.COMPENSATING;
            d.attempt = 0;
            d.inFlight = false;
            d.nextAttemptAt = now;
            d.lastError = code;
            if (from < 0) {
                d.status = SagaStatus.COMPENSATED;
                d.stepIndex = 0;
            } else {
                d.stepIndex = from;
            }
        }));
    }

    private Mono<SagaInstance> escalate(SagaInstance saga, SagaDefinition definition, String code, Instant now) {
        return persist(saga, saga.change(now, d -> {
            d.status = SagaStatus.NEEDS_INTERVENTION;
            d.inFlight = false;
            d.lastError = code;
        }));
    }

    // ---- signals and people -----------------------------------------------------------------------------------------------

    /**
     * The answer to a step that returned {@link StepOutcome.Waiting}, delivered as an event. It is applied only if the saga is
     * still waiting on that step in that direction; a duplicate or a late answer is ignored (false).
     */
    public Mono<Boolean> signal(UUID sagaId, String step, SagaPhase phase, StepOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return store.find(sagaId).flatMap(saga -> {
            SagaDefinition definition = definitions.get(saga.type());
            if (definition == null || saga.status() != SagaStatus.RUNNING || !saga.inFlight() || saga.phase() != phase
                    || !definition.steps().get(saga.stepIndex()).name().equals(step)) {
                return Mono.just(false);
            }
            Instant now = clock.instant();
            Mono<SagaInstance> moved = switch (outcome) {
                case StepOutcome.Done done -> completeStep(saga, definition, done.data(), now);
                case StepOutcome.Failed failed -> definitiveFailure(saga, definition, failed.code(), now);
                case StepOutcome.Waiting waiting -> Mono.<SagaInstance>empty();
            };
            return moved.map(next -> true).defaultIfEmpty(false);
        }).defaultIfEmpty(false);
    }

    /**
     * The owning team's decision for a saga in the manual queue. {@code by} is who decided (recorded by the listener's audit).
     * False if the saga is not in the queue.
     */
    public Mono<Boolean> resolve(UUID sagaId, ManualResolution resolution, String by) {
        Objects.requireNonNull(resolution, "resolution");
        if (by == null || by.isBlank()) {
            return Mono.error(new IllegalArgumentException("a resolution says who made it"));
        }
        return store.find(sagaId).flatMap(saga -> {
            if (saga.status() != SagaStatus.NEEDS_INTERVENTION) {
                return Mono.just(false);
            }
            Instant now = clock.instant();
            SagaInstance next = resolution == ManualResolution.RETRY
                    ? saga.change(now, d -> {
                        d.status = SagaStatus.RUNNING;
                        d.attempt = 0;
                        d.inFlight = false;
                        d.nextAttemptAt = now;
                    })
                    : saga.change(now, d -> {
                        d.status = SagaStatus.COMPENSATED;
                        d.inFlight = false;
                        d.lastError = "resolved-by-hand";
                    });
            return persist(saga, next).map(moved -> true).defaultIfEmpty(false);
        }).defaultIfEmpty(false);
    }

    // ---- seeing -----------------------------------------------------------------------------------------------------------

    /** The dashboard and the alert: counts, sagas nobody has moved for {@code stuckAfter} past their time, and the manual queue. */
    public Mono<SagaReport> report(Duration stuckAfter) {
        Instant before = clock.instant().minus(stuckAfter);
        return Mono.zip(store.counts(), store.overdue(before, 200).collectList(),
                store.byStatus(SagaStatus.NEEDS_INTERVENTION, 200).collectList())
                .map(t -> new SagaReport(t.getT1(), t.getT2(), t.getT3()));
    }

    public Mono<SagaInstance> find(UUID id) {
        return store.find(id);
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------------------

    /** Stable for every attempt, different for the action and the compensation. */
    static String idempotencyKey(UUID sagaId, String step, SagaPhase phase) {
        return sagaId + ":" + step + ":" + (phase == SagaPhase.FORWARD ? "do" : "undo");
    }

    private Mono<Boolean> cas(SagaInstance from, SagaInstance to) {
        return store.save(to, from.version());
    }

    private Mono<SagaInstance> persist(SagaInstance from, SagaInstance to) {
        return cas(from, to).flatMap(won -> {
            if (won) {
                notifyListener(to);
                return Mono.just(to);
            }
            return Mono.<SagaInstance>empty();
        });
    }

    private void notifyListener(SagaInstance saga) {
        try {
            listener.onChange(saga);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "saga listener failed: {0}", e.getClass().getSimpleName());
        }
    }
}
