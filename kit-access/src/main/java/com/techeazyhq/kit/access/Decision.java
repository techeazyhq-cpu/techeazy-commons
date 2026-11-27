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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the Policy Decision Point said. {@code allow} is true only when it said so in as many words; {@code undetermined} is
 * "neither allow nor deny" (the facts it needed did not arrive): the caller tries later, it is not a refusal. {@code reasons}
 * are codes for logs and support, never for the caller's message. {@code obligations} are what the PEP must do for an allow
 * to stand (write the audit event, queue the break-glass review...).
 */
public record Decision(boolean allow, boolean undetermined, List<String> reasons, boolean breakGlass,
                       Map<String, Object> obligations) {

    public Decision {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        obligations = Map.copyOf(Objects.requireNonNull(obligations, "obligations"));
        if (allow && undetermined) {
            throw new IllegalArgumentException("A decision is not both allowed and undetermined");
        }
    }

    public static Decision deny(String... reasons) {
        return new Decision(false, false, List.of(reasons), false, Map.of());
    }

    /**
     * Reads the policy's answer ({@code {allow, undetermined, reasons, break_glass, obligations}}). Strict: a field of the wrong
     * type, or no {@code allow} at all, is not understood and throws, and the PEP treats that as unavailable. It never
     * defaults a missing {@code allow} to anything.
     */
    public static Decision parse(Map<String, ?> result) {
        Objects.requireNonNull(result, "result");
        boolean allow = flag(result, "allow", true);
        boolean undetermined = flag(result, "undetermined", false);
        boolean breakGlass = flag(result, "break_glass", false);
        Object rawReasons = result.containsKey("reasons") ? result.get("reasons") : List.of();
        if (!(rawReasons instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new IllegalArgumentException("reasons must be a list of codes");
        }
        Object rawObligations = result.containsKey("obligations") ? result.get("obligations") : Map.of();
        if (!(rawObligations instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(String.class::isInstance)) {
            throw new IllegalArgumentException("obligations must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> obligations = (Map<String, Object>) map;
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) list;
        return new Decision(allow, undetermined, reasons, breakGlass, obligations);
    }

    private static boolean flag(Map<String, ?> result, String name, boolean required) {
        Object value = result.get(name);
        if (value == null && !required) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return flag;
    }
}
