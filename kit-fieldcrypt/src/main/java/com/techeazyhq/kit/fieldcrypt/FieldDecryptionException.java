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
 * A sealed value could not be opened: it was altered, it is malformed, or it was opened for a different row, column or tenant
 * than it was sealed for. The message is the same for every cause (so it tells an attacker nothing) and never holds the value.
 */
public final class FieldDecryptionException extends RuntimeException {

    public FieldDecryptionException() {
        super("could not decrypt the field");
    }
}
