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

package com.techeazyhq.kit.fieldcrypt;

/**
 * The key is gone: the data it protected has been erased on purpose (crypto-shredding) or the key never existed. It is not a
 * fault to retry; the caller treats the value as erased.
 */
public final class KeyDestroyedException extends RuntimeException {

    public KeyDestroyedException(String keyId) {
        super("key destroyed or unknown: " + keyId);
    }
}
