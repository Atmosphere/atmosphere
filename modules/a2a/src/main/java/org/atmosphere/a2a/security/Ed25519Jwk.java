/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.a2a.security;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ed25519 key handling for A2A AgentCard JWS signatures, using only the JDK
 * provider (no third-party crypto). Converts between a JDK
 * {@link EdECPublicKey} and the JWK {@code OKP}/{@code Ed25519} representation
 * embedded in {@link org.atmosphere.a2a.types.AgentCardSignature} headers so a
 * verifier can reconstruct the public key from the wire.
 *
 * <p>The public-key wire form is the 32-byte RFC 8032 encoding (the curve
 * point's {@code y} coordinate, little-endian, with the sign bit of {@code x}
 * in the most-significant bit of the last byte), base64url-encoded as the JWK
 * {@code "x"} parameter — exactly what other JOSE/A2A implementations expect.</p>
 */
public final class Ed25519Jwk {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private Ed25519Jwk() {
        // static utility
    }

    /** Generate a fresh Ed25519 key pair. */
    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            // Ed25519 is mandated by the JDK since 15; absence is a broken JRE.
            throw new IllegalStateException("Ed25519 unavailable in this JRE", e);
        }
    }

    /** JWK ({@code kty=OKP}, {@code crv=Ed25519}) for a public key. */
    public static Map<String, Object> toJwk(EdECPublicKey publicKey) {
        var jwk = new LinkedHashMap<String, Object>();
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", B64URL.encodeToString(rawPublicKey(publicKey)));
        return jwk;
    }

    /** Reconstruct a public key from a JWK {@code OKP}/{@code Ed25519} map. */
    public static EdECPublicKey fromJwk(Map<String, ?> jwk) {
        if (jwk == null
                || !"OKP".equals(jwk.get("kty"))
                || !"Ed25519".equals(jwk.get("crv"))
                || !(jwk.get("x") instanceof String x)) {
            throw new IllegalArgumentException("not an Ed25519 OKP JWK: " + jwk);
        }
        return publicKeyFromRaw(B64URL_DEC.decode(x));
    }

    /** 32-byte RFC 8032 little-endian encoding of an Ed25519 public key. */
    public static byte[] rawPublicKey(EdECPublicKey publicKey) {
        var point = publicKey.getPoint();
        var le = toUnsigned32LittleEndian(point.getY());
        if (point.isXOdd()) {
            le[31] |= (byte) 0x80;
        }
        return le;
    }

    /** Inverse of {@link #rawPublicKey}: decode the 32-byte form to a key. */
    public static EdECPublicKey publicKeyFromRaw(byte[] raw) {
        if (raw.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes, got " + raw.length);
        }
        var le = raw.clone();
        var xOdd = (le[31] & 0x80) != 0;
        le[31] &= 0x7f;
        // Reverse little-endian -> big-endian for BigInteger.
        var be = new byte[32];
        for (var i = 0; i < 32; i++) {
            be[i] = le[31 - i];
        }
        var y = new BigInteger(1, be);
        try {
            var kf = KeyFactory.getInstance("Ed25519");
            return (EdECPublicKey) kf.generatePublic(
                    new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid Ed25519 public key encoding", e);
        }
    }

    private static byte[] toUnsigned32LittleEndian(BigInteger v) {
        var be = v.toByteArray();           // big-endian, two's complement (may have leading 0x00)
        var fixed = new byte[32];
        var copy = Math.min(be.length, 32);
        // Right-align the least-significant 32 bytes into a big-endian buffer...
        System.arraycopy(be, be.length - copy, fixed, 32 - copy, copy);
        // ...then reverse to little-endian.
        var le = new byte[32];
        for (var i = 0; i < 32; i++) {
            le[i] = fixed[31 - i];
        }
        return le;
    }
}
