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

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares a projection with its source, by key: the check behind "a projection rebuilt from events equals the source" and the
 * reconciliation job that compares projections with sources in production (STD-DB-06). The differences name keys, never values.
 */
public final class Reconciliation {

    private Reconciliation() {
    }

    /** Keys the projection lacks, keys it has that the source does not, and keys whose values differ. */
    public record Diff(Set<String> missing, Set<String> unexpected, Set<String> changed) {

        public boolean isEmpty() {
            return missing.isEmpty() && unexpected.isEmpty() && changed.isEmpty();
        }
    }

    public static Diff compare(Map<String, ?> source, Map<String, ?> projection) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(projection, "projection");
        Set<String> missing = new TreeSet<>();
        Set<String> changed = new TreeSet<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (!projection.containsKey(entry.getKey())) {
                missing.add(entry.getKey());
            } else if (!Objects.equals(entry.getValue(), projection.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        Set<String> unexpected = new LinkedHashSet<>(projection.keySet());
        unexpected.removeAll(source.keySet());
        return new Diff(missing, new TreeSet<>(unexpected), changed);
    }
}
