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

package com.techeazyhq.kit.ratelimit;

/**
 * A token bucket: it holds up to {@code capacity} requests (the burst a key may make at once) and refills {@code refillPerSecond}
 * requests a second (the rate it may keep up). A key that is quiet for a while gets its full burst back.
 */
public record Limit(int capacity, double refillPerSecond) {

    public Limit {
        if (capacity < 1) {
            throw new IllegalArgumentException("a limit allows at least one request");
        }
        if (!(refillPerSecond > 0) || Double.isInfinite(refillPerSecond)) {
            throw new IllegalArgumentException("a limit refills at a positive rate");
        }
    }
}
