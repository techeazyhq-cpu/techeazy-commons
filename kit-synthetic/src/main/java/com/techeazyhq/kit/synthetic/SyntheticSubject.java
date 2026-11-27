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

package com.techeazyhq.kit.synthetic;

import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The reserved identity space of synthetic subjects: until real data is allowed, every person in a development, test or
 * demo environment is synthetic, and anything that stands in for a real control (a stub consent adapter, say) answers
 * DENY for anyone else. This class is the one definition of what "synthetic" means, so a data generator, such a stub and
 * the checks agree. It depends on nothing, so production code may use it.
 *
 * <p>A synthetic subject id is a UUID whose first 32 bits are {@link #MARKER} ("SYN" and a zero byte), so a leaked
 * one is recognisable in a database, a log or an event: {@code 53594e00-....}. Its reference, where a subject is named
 * as text (the Consent Port, events), is {@link #PREFIX} followed by the id: {@code syn:53594e00-....}. A real patient
 * id is a random UUID, so it carries the marker only by a 1 in 4 billion accident; the reference prefix is what a
 * guard checks first, and both checks must pass.
 */
public final class SyntheticSubject {

    /** The first 32 bits of every synthetic id: 0x53 'S', 0x59 'Y', 0x4E 'N', 0x00. */
    public static final long MARKER = 0x53594E00L;

    /** The start of every synthetic subject reference. */
    public static final String PREFIX = "syn:";

    private SyntheticSubject() {
    }

    /** A new synthetic id from the given source of randomness (seeded, for repeatable data). */
    public static UUID newId(RandomGenerator random) {
        long msb = (MARKER << 32) | (random.nextInt() & 0xFFFFFFFFL);
        msb = (msb & ~0xF000L) | 0x8000L;                       // version 8: vendor-defined, never a random v4
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant 10
        return new UUID(msb, lsb);
    }

    public static boolean isSyntheticId(UUID id) {
        return id != null && (id.getMostSignificantBits() >>> 32) == MARKER;
    }

    /** The reference for a synthetic id. Refuses an id that is not synthetic. */
    public static String ref(UUID id) {
        if (!isSyntheticId(id)) {
            throw new IllegalArgumentException("Not a synthetic subject id: " + id);
        }
        return PREFIX + id;
    }

    /** Whether the text is a well-formed synthetic subject reference: the prefix and a synthetic id, nothing else. */
    public static boolean isSyntheticRef(String ref) {
        if (ref == null || !ref.startsWith(PREFIX)) {
            return false;
        }
        try {
            UUID id = UUID.fromString(ref.substring(PREFIX.length()));
            return isSyntheticId(id) && ref.equals(PREFIX + id);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** For a guard: returns the id, or throws if the reference is not a synthetic subject. */
    public static UUID requireSyntheticRef(String ref) {
        if (!isSyntheticRef(ref)) {
            throw new IllegalArgumentException("Not a synthetic subject reference");
        }
        return UUID.fromString(ref.substring(PREFIX.length()));
    }
}
