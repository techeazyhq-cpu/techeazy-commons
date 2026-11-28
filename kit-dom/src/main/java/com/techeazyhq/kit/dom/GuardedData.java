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

package com.techeazyhq.kit.dom;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a port (a repository, a reader of a read model) that reaches data which must not be read or written before the caller has
 * been checked: the access policy always, and the consent check as well when the data is personal ({@code D3} or {@code D4}).
 * Put it on the interface the domain or the application layer defines. The architecture test of the service
 * ({@code LayeredArchitectureTest}) then fails the build for any use case or handler that depends on a marked port and does not
 * depend on the guards, so a new handler cannot be written that forgets them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GuardedData {

    /** The data class of what the port reaches: {@code D0} to {@code D4} (STD-DATA-01). D3 and D4 also need the consent check. */
    String classification();
}
