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

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A running (or finished) saga, as it is persisted: which saga, which step, which direction, how many attempts, and when the
 * engine must look at it next ({@code nextAttemptAt}: a retry time, or the deadline of the step in flight). {@code data} holds
 * ids and facts the steps pass on, <b>never</b> sensitive values: it is stored, shown on the dashboard and logged.
 * Immutable; {@code version} is bumped on every change so two workers cannot both move it.
 */
public record SagaInstance(UUID id, UUID tenantId, String type, String correlationId, SagaStatus status, SagaPhase phase,
                           int stepIndex, int attempt, boolean inFlight, Instant nextAttemptAt, Map<String, String> data,
                           String lastError, String owner, long version, Instant createdAt, Instant updatedAt) {

    public static final int MAX_DATA_ENTRIES = 50;
    public static final int MAX_VALUE_LENGTH = 256;

    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");
    private static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9:._-]{1,128}");

    public SagaInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        if (correlationId == null || !CORRELATION.matcher(correlationId).matches()) {
            throw new IllegalArgumentException("correlationId must be a plain id");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        data = checked(data);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    static Map<String, String> checked(Map<String, String> data) {
        Objects.requireNonNull(data, "data");
        if (data.size() > MAX_DATA_ENTRIES) {
            throw new IllegalArgumentException("a saga carries at most " + MAX_DATA_ENTRIES + " data entries");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (key == null || !KEY.matcher(key).matches() || value == null || value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("saga data is plain keys and short values (ids), never records");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    /** A copy with changes; the version goes up by one and {@code updatedAt} is set. */
    SagaInstance change(Instant now, Consumer<Draft> changes) {
        Draft d = new Draft(this);
        changes.accept(d);
        return new SagaInstance(id, tenantId, type, correlationId, d.status, d.phase, d.stepIndex, d.attempt, d.inFlight,
                d.nextAttemptAt, d.data, d.lastError, owner, version + 1, createdAt, now);
    }

    /** Never prints the data. */
    @Override
    public String toString() {
        return "SagaInstance[" + type + " " + id + " " + status + " " + phase + " step=" + stepIndex + " attempt=" + attempt + "]";
    }

    /** The changeable parts, for {@link #change}. */
    static final class Draft {
        SagaStatus status;
        SagaPhase phase;
        int stepIndex;
        int attempt;
        boolean inFlight;
        Instant nextAttemptAt;
        Map<String, String> data;
        String lastError;

        private Draft(SagaInstance from) {
            status = from.status;
            phase = from.phase;
            stepIndex = from.stepIndex;
            attempt = from.attempt;
            inFlight = from.inFlight;
            nextAttemptAt = from.nextAttemptAt;
            data = from.data;
            lastError = from.lastError;
        }
    }
}
