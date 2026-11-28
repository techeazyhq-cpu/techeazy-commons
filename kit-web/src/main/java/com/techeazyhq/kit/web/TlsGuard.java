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

package com.techeazyhq.kit.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the TLS key of a service may be used where it is (STD-TLS-01). A service serves TLS only, so its key is the
 * identity every client trusts; a development key, or one protected by a password everyone knows, must never reach a real
 * environment. {@link #require} is called at start-up and refuses to let the service start:
 * <ul>
 *   <li>always: a keystore location and a password must be set (there is no default for either);</li>
 *   <li>outside a development run: the password is not a well-known one ({@code changeit}, {@code password}...); the keystore opens
 *       with it and holds a private key; the server certificate is in date, is <b>not self-signed</b> and is not for
 *       {@code localhost}, which is what a development certificate is.</li>
 * </ul>
 * A development run (a developer's machine, a test) says so explicitly; it is never the default. The password is never in a message.
 */
public final class TlsGuard {

    private static final Set<String> WEAK = Set.of("changeit", "changeme", "password", "passw0rd", "secret", "keystore", "test",
            "testing", "default", "admin", "123456", "12345678", "qwerty", "letmein");

    private TlsGuard() {
    }

    /** Refuses (an {@link IllegalStateException}) unless the key may be used. {@code location} is a path or a {@code file:} URL. */
    public static void require(String location, String type, String password, boolean development) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("No TLS keystore is set (OGH_TLS_KEYSTORE): a service serves TLS only and does not start without a key");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("No TLS keystore password is set (OGH_TLS_KEYSTORE_PASSWORD): there is no default for it");
        }
        if (development) {
            return;
        }
        if (WEAK.contains(password.toLowerCase(Locale.ROOT)) || password.length() < 8) {
            throw new IllegalStateException("The TLS keystore password is a well-known or short one; a real environment sets its own");
        }
        KeyStore keyStore = open(location, type == null || type.isBlank() ? "PKCS12" : type, password);
        X509Certificate server = serverCertificate(keyStore);
        try {
            server.checkValidity(Date.from(Instant.now()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The TLS server certificate is expired or not yet valid");
        }
        if (server.getSubjectX500Principal().equals(server.getIssuerX500Principal())) {
            throw new IllegalStateException("The TLS server certificate is self-signed: a development certificate is not used outside a "
                    + "development run (ogh.tls.development)");
        }
        if (server.getSubjectX500Principal().getName().toLowerCase(Locale.ROOT).contains("cn=localhost")) {
            throw new IllegalStateException("The TLS server certificate is for localhost: it is a development certificate");
        }
    }

    private static KeyStore open(String location, String type, String password) {
        Path path = Path.of(location.startsWith("file:") ? location.substring("file:".length()) : location);
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(in, password.toCharArray());
            return keyStore;
        } catch (IOException | GeneralSecurityException e) {
            // the cause may name the password or the path; neither belongs in a message
            throw new IllegalStateException("The TLS keystore could not be opened with the password it was given");
        }
    }

    private static X509Certificate serverCertificate(KeyStore keyStore) {
        try {
            for (String alias : Collections.list(keyStore.aliases())) {
                if (keyStore.isKeyEntry(alias)) {
                    Certificate certificate = keyStore.getCertificate(alias);
                    if (certificate instanceof X509Certificate x509) {
                        return x509;
                    }
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The TLS keystore could not be read");
        }
        throw new IllegalStateException("The TLS keystore holds no private key");
    }
}
