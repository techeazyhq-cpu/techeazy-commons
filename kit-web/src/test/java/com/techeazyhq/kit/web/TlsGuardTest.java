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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A development key, a well-known password and a key nobody vouches for never reach a real environment. */
class TlsGuardTest {

    private static final String STRONG = "n0t-a-known-pass-1";

    @TempDir
    static Path dir;

    private static Path devLike;
    private static Path selfSignedProd;
    private static Path signedStrong;
    private static Path signedWeak;
    private static Path expired;
    private static Path trustOnly;

    private static void keytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor() == 0, "keytool " + String.join(" ", args) + " failed: " + output);
    }

    /** A server key signed by a test CA, as a real environment's would be. */
    private static Path signed(String name, String password) throws Exception {
        Path ca = dir.resolve("ca-" + name + ".p12");
        Path server = dir.resolve(name + ".p12");
        Path csr = dir.resolve(name + ".csr");
        Path cert = dir.resolve(name + ".crt");
        Path root = dir.resolve("root-" + name + ".crt");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Test CA", "-ext", "bc:c",
                "-storetype", "PKCS12", "-keystore", ca.toString(), "-storepass", "ca-password-1", "-validity", "30");
        keytool("-exportcert", "-alias", "ca", "-keystore", ca.toString(), "-storepass", "ca-password-1", "-rfc", "-file", root.toString());
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=svc.example", "-storetype", "PKCS12",
                "-keystore", server.toString(), "-storepass", password, "-keypass", password, "-validity", "30");
        keytool("-certreq", "-alias", "server", "-keystore", server.toString(), "-storepass", password, "-file", csr.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", ca.toString(), "-storepass", "ca-password-1", "-infile", csr.toString(),
                "-outfile", cert.toString(), "-rfc", "-ext", "san=dns:svc.example", "-validity", "30");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", root.toString(), "-keystore", server.toString(), "-storepass", password);
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", cert.toString(), "-keystore", server.toString(), "-storepass", password);
        return server;
    }

    @BeforeAll
    static void keys() throws Exception {
        devLike = dir.resolve("dev.p12");
        keytool("-genkeypair", "-alias", "ogh-dev", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-storetype", "PKCS12",
                "-keystore", devLike.toString(), "-storepass", "changeit", "-keypass", "changeit", "-validity", "30");
        selfSignedProd = dir.resolve("self.p12");
        keytool("-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=svc.example", "-storetype", "PKCS12",
                "-keystore", selfSignedProd.toString(), "-storepass", STRONG, "-keypass", STRONG, "-validity", "30");
        signedStrong = signed("strong", STRONG);
        signedWeak = signed("weak", "changeit");
        expired = dir.resolve("expired.p12");
        keytool("-genkeypair", "-alias", "e", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=old.example", "-storetype", "PKCS12",
                "-keystore", expired.toString(), "-storepass", STRONG, "-keypass", STRONG, "-validity", "1", "-startdate", "-5d");
        trustOnly = dir.resolve("trust.p12");
        Path ca = dir.resolve("root-strong.crt");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", ca.toString(), "-storetype", "PKCS12", "-keystore", trustOnly.toString(),
                "-storepass", STRONG);
    }

    private static String message(Path keystore, String password, boolean development) {
        return assertThrows(IllegalStateException.class, () -> TlsGuard.require(keystore.toString(), "PKCS12", password, development)).getMessage();
    }

    @Test
    void aDevelopmentRunMayUseTheDevelopmentKeyAndItsPassword() {
        assertDoesNotThrow(() -> TlsGuard.require("file:" + devLike, "PKCS12", "changeit", true));
    }

    @Test
    void thereIsNoDefaultKeystoreAndNoDefaultPasswordEvenInADevelopmentRun() {
        assertTrue(assertThrows(IllegalStateException.class, () -> TlsGuard.require("", "PKCS12", "x", true)).getMessage().contains("keystore"));
        assertThrows(IllegalStateException.class, () -> TlsGuard.require(null, "PKCS12", "x", true));
        assertTrue(assertThrows(IllegalStateException.class, () -> TlsGuard.require(devLike.toString(), "PKCS12", "", true)).getMessage()
                .contains("password"));
        assertThrows(IllegalStateException.class, () -> TlsGuard.require(devLike.toString(), "PKCS12", null, true));
    }

    @Test
    void outsideADevelopmentRunTheDevelopmentKeyIsRefused() {
        assertFalse(message(devLike, "changeit", false).isEmpty());
        assertTrue(message(devLike, STRONG, false).contains("could not be opened"), "wrong password is a refusal, not a crash");
    }

    @Test
    void outsideADevelopmentRunAWellKnownOrShortPasswordIsRefusedWhateverTheKey() {
        assertTrue(message(signedWeak, "changeit", false).contains("well-known"));
        for (String weak : List.of("password", "Secret", "123456", "short")) {
            assertTrue(message(signedStrong, weak, false).contains("well-known"), weak);
        }
    }

    @Test
    void outsideADevelopmentRunASelfSignedKeyIsRefusedEvenWithAStrongPassword() {
        assertTrue(message(selfSignedProd, STRONG, false).contains("self-signed"));
    }

    @Test
    void aKeySignedByAnIssuerWithAStrongPasswordIsAccepted() {
        assertDoesNotThrow(() -> TlsGuard.require(signedStrong.toString(), "PKCS12", STRONG, false));
        assertDoesNotThrow(() -> TlsGuard.require("file:" + signedStrong, null, STRONG, false));
    }

    @Test
    void anExpiredKeyAKeystoreWithNoPrivateKeyAndAMissingFileAreRefused() {
        assertTrue(message(expired, STRONG, false).contains("expired") || message(expired, STRONG, false).contains("self-signed"));
        assertTrue(message(trustOnly, STRONG, false).contains("no private key"));
        assertTrue(message(dir.resolve("missing.p12"), STRONG, false).contains("could not be opened"));
    }

    @Test
    void aMessageNeverHoldsThePasswordOrThePath() {
        String message = message(signedStrong, "wrong-but-long-password", false);

        assertFalse(message.contains("wrong-but-long-password") || message.contains(dir.toString()), message);
        assertFalse(message(devLike, "changeit", false).contains("changeit"));
    }
}
