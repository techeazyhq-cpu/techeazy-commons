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

/**
 * A place in a product where the Consent Port is asked (reading a sensitive record, sending a notification, sharing with another
 * tenant, giving an AI model context, and so on). The product names its sites, usually as an enum that implements this, and
 * says for each which purposes it may be used for and how long it waits for the answer. {@link ConsentGuard} enforces both.
 *
 * <p>An implementation must be usable as a map key: an enum constant is.
 */
public interface ConsentCallSite {

    /** How long a check at this site waits before it is treated as "try later". Positive. */
    Duration timeout();

    /** Whether this site may be used for the purpose. A site cannot quietly ask about a purpose that is not its own. */
    boolean accepts(PurposeCode purpose);
}
