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

package com.techeazyhq.kit.cqrs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The scheduled check that a read model still equals its source (STD-DB-06). A projection changed outside the projector (an
 * operator, a bug, a restore) is otherwise never noticed. For every tenant it compares the source with the projection, by key, and
 * tells {@link Alerts} the keys that differ (never values).
 *
 * <ul>
 *   <li><b>No false alarms from lag.</b> While the projection is behind its log nothing is compared, and a difference is raised
 *       only when a second comparison, made after the first, shows it again: a write that lands between the two reads is not an
 *       alert.</li>
 *   <li><b>One tenant never hides another.</b> A tenant whose comparison fails is reported as failed and the run goes on.</li>
 *   <li><b>Both sides count.</b> The tenants are whatever the caller lists, so list those of the source and of the projection: a
 *       tenant that exists only in the projection is all "unexpected".</li>
 * </ul>
 * Tenants are plain strings so the job fits any domain and any store; the source and the projection are read per tenant.
 */
public final class ReconciliationJob {

    /** Where the outcome goes: a log line, a metric, a pager. Implementations must not throw. */
    public interface Alerts {

        /** The projection and its source differ for this tenant. {@code diff} names keys only. */
        void diverged(String projection, String tenant, Reconciliation.Diff diff);

        /** The comparison for this tenant could not be made. {@code failure} is passed on for the log, not for a message. */
        void failed(String projection, String tenant, Throwable failure);

        /** The run was not made because the projection is still catching up. */
        void skipped(String projection, long lag);

        /** A run finished: how many tenants were compared, how many differed. */
        void completed(String projection, int tenantsCompared, int tenantsDiverged);
    }

    /** What one run found for one tenant. */
    public record Result(String tenant, Reconciliation.Diff diff, boolean failed) {
    }

    private final String projection;
    private final Supplier<Flux<String>> tenants;
    private final Function<String, Mono<Map<String, ?>>> source;
    private final Function<String, Mono<Map<String, ?>>> view;
    private final Supplier<Mono<Long>> lag;
    private final Alerts alerts;

    /**
     * @param tenants every tenant either side may hold
     * @param source  the source of truth, one tenant's keys and fingerprints of their values
     * @param view    the projection, in the same shape
     * @param lag     events the projection has not applied yet, for example {@code runner::lag}
     */
    public ReconciliationJob(String projection, Supplier<Flux<String>> tenants, Function<String, Mono<Map<String, ?>>> source,
                             Function<String, Mono<Map<String, ?>>> view, Supplier<Mono<Long>> lag, Alerts alerts) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.source = Objects.requireNonNull(source, "source");
        this.view = Objects.requireNonNull(view, "view");
        this.lag = Objects.requireNonNull(lag, "lag");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
    }

    /** One run. The results of the tenants that were compared; empty when the projection was behind. */
    public Mono<List<Result>> runOnce() {
        return lag.get().flatMap(behind -> {
            if (behind > 0) {
                alerts.skipped(projection, behind);
                return Mono.just(List.<Result>of());
            }
            return tenants.get().distinct().concatMap(this::check).collectList().doOnNext(results -> alerts.completed(projection,
                    results.size(), (int) results.stream().filter(r -> r.failed() || !r.diff().isEmpty()).count()));
        });
    }

    private Mono<Result> check(String tenant) {
        return compare(tenant).flatMap(first -> first.isEmpty() ? Mono.just(first) : compare(tenant)).map(diff -> {
            if (!diff.isEmpty()) {
                alerts.diverged(projection, tenant, diff);
            }
            return new Result(tenant, diff, false);
        }).onErrorResume(failure -> {
            alerts.failed(projection, tenant, failure);
            return Mono.just(new Result(tenant, new Reconciliation.Diff(java.util.Set.of(), java.util.Set.of(), java.util.Set.of()), true));
        });
    }

    private Mono<Reconciliation.Diff> compare(String tenant) {
        return Mono.zip(source.apply(tenant), view.apply(tenant), Reconciliation::compare);
    }

    /** Runs now and then every {@code interval}; a run that fails is reported and the next still happens. */
    public Disposable schedule(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        return Flux.interval(Duration.ZERO, interval).onBackpressureDrop().concatMap(tick -> runOnce().onErrorResume(failure -> {
            alerts.failed(projection, "*", failure);
            return Mono.empty();
        })).subscribe();
    }

    /** Alerts to the platform logger: the keys that differ and how many, at ERROR; never a value. */
    public static final class LoggingAlerts implements Alerts {

        private final System.Logger logger = System.getLogger("kit.reconciliation");

        @Override
        public void diverged(String projection, String tenant, Reconciliation.Diff diff) {
            logger.log(System.Logger.Level.ERROR, "read model {0} differs from its source for tenant {1}: missing {2}, unexpected {3}, "
                    + "changed {4}", projection, tenant, diff.missing(), diff.unexpected(), diff.changed());
        }

        @Override
        public void failed(String projection, String tenant, Throwable failure) {
            logger.log(System.Logger.Level.ERROR, "read model {0} could not be compared for tenant {1}: {2}", projection, tenant,
                    failure.getClass().getSimpleName());
        }

        @Override
        public void skipped(String projection, long lag) {
            logger.log(System.Logger.Level.INFO, "read model {0} is {1} events behind, not compared this time", projection, lag);
        }

        @Override
        public void completed(String projection, int tenantsCompared, int tenantsDiverged) {
            logger.log(System.Logger.Level.DEBUG, "read model {0}: {1} tenants compared, {2} differ", projection, tenantsCompared,
                    tenantsDiverged);
        }
    }
}
