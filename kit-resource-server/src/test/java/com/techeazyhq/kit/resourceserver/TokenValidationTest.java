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

package com.techeazyhq.kit.resourceserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.test.StepVerifier;

/** A token is accepted only if it is signed by the realm's key, in date, from this issuer and for this service. */
class TokenValidationTest {

    private static final String ISSUER = "https://idp.example/realms/acme";
    private static final String AUDIENCE = "acme-orders-svc";
    private static final RSAKey KEY = generate("k1");
    private static final RSAKey OTHER = generate("k2");

    private final ReactiveJwtDecoder decoder;

    TokenValidationTest() throws Exception {
        decoder = TokenValidation.decoder(KEY.toRSAPublicKey(), ISSUER, AUDIENCE);
    }

    private static RSAKey generate(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(RSAKey signer, String issuer, Object audience, Instant expires) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject("u1").expirationTime(Date.from(expires));
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (audience instanceof String single) {
            claims.audience(single);
        } else if (audience instanceof List<?> list) {
            claims.audience((List<String>) list);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    private static Instant later() {
        return Instant.now().plusSeconds(600);
    }

    private void refused(String token) {
        StepVerifier.create(decoder.decode(token)).expectError(JwtException.class).verify();
    }

    @Test
    void aTokenFromThisIssuerForThisServiceIsAccepted() throws Exception {
        StepVerifier.create(decoder.decode(token(KEY, ISSUER, AUDIENCE, later())))
                .assertNext(jwt -> assertEquals("u1", jwt.getSubject())).verifyComplete();
    }

    @Test
    void aTokenForSeveralAudiencesIsAcceptedWhenOneOfThemIsThisService() throws Exception {
        StepVerifier.create(decoder.decode(token(KEY, ISSUER, List.of("other-svc", AUDIENCE), later()))).expectNextCount(1).verifyComplete();
    }

    @Test
    void aTokenFromAnotherIssuerIsRefusedEvenWhenItsSignatureIsGood() throws Exception {
        refused(token(KEY, "https://idp.example/realms/other", AUDIENCE, later()));
        refused(token(KEY, ISSUER + "/", AUDIENCE, later()));
        refused(token(KEY, null, AUDIENCE, later()));
    }

    @Test
    void aTokenForAnotherServiceOrForNoOneIsRefused() throws Exception {
        refused(token(KEY, ISSUER, "other-svc", later()));
        refused(token(KEY, ISSUER, List.of("other-svc", "another-svc"), later()));
        refused(token(KEY, ISSUER, null, later()));
        refused(token(KEY, ISSUER, AUDIENCE + "x", later()));
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        refused(token(KEY, ISSUER, AUDIENCE, Instant.now().minusSeconds(3600)));
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefused() throws Exception {
        refused(token(OTHER, ISSUER, AUDIENCE, later()));
    }

    @Test
    void aTokenWithNoSignatureOrASymmetricOneIsRefused() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("u1").expirationTime(Date.from(later())).build();
        refused(new PlainJWT(new PlainHeader(), claims).serialize());
        SignedJWT hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        hs.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
        refused(hs.serialize());
        refused("not.a.token");
        refused("");
    }

    @Test
    void aServiceNotToldItsIssuerOrItsAudienceDoesNotStart() {
        assertTrue(assertThrows(IllegalStateException.class, () -> TokenValidation.require("", AUDIENCE)).getMessage().contains("issuer"));
        assertThrows(IllegalStateException.class, () -> TokenValidation.require(null, AUDIENCE));
        assertThrows(IllegalStateException.class, () -> TokenValidation.require("   ", AUDIENCE));
        assertTrue(assertThrows(IllegalStateException.class, () -> TokenValidation.require(ISSUER, "")).getMessage().contains("audience"));
        assertThrows(IllegalStateException.class, () -> TokenValidation.require(ISSUER, null));
        assertThrows(IllegalStateException.class, () -> TokenValidation.require(ISSUER, "has space"));
        assertThrows(IllegalStateException.class, () -> TokenValidation.decoder("https://idp.example/certs", "", AUDIENCE));
    }

    @Test
    void theIssuerIsHttpsOrTheLoopbackOfADevelopersMachineAndNothingElse() {
        TokenValidation.require("https://idp.example/realms/acme", AUDIENCE);
        TokenValidation.require("http://localhost:8080/realms/acme", AUDIENCE);
        TokenValidation.require("http://127.0.0.1:8080/realms/acme", AUDIENCE);
        assertThrows(IllegalStateException.class, () -> TokenValidation.require("http://idp.example/realms/acme", AUDIENCE));
        assertThrows(IllegalStateException.class, () -> TokenValidation.require("ftp://localhost/realms", AUDIENCE));
        assertThrows(IllegalStateException.class, () -> TokenValidation.require("idp.example", AUDIENCE));
    }
}
