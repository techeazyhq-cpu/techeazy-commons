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

package com.techeazyhq.kit.requestcontext;

/**
 * The token for an internal call could not be obtained (the identity provider refused, was down, or answered something unusable). The
 * call is not made, and the caller's own token is never sent instead. The message holds nothing the identity provider said.
 */
public final class TokenExchangeException extends RuntimeException {

    public TokenExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
