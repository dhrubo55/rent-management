package com.renteasebd.security;

import com.renteasebd.common.AppException;
import com.renteasebd.domain.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final JwtProperties jwtProperties;
    private KeyPair ephemeralKeyPair;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public String generateAccessToken(User user, String sessionId) {
        Instant now = Instant.now();
        Instant exp = now.plus(jwtProperties.getAccessTokenTtl());

        return Jwts.builder()
            .subject(String.valueOf(user.getId()))
            .claim("email", user.getEmail())
            .claim("role", user.getRole().name())
            .claim("sid", sessionId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(resolvePrivateKey())
            .compact();
    }

    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                .verifyWith(resolvePublicKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (Exception ex) {
            throw new AppException("TOKEN_INVALID", "Invalid or expired access token", "accessToken");
        }
    }

    private PrivateKey resolvePrivateKey() {
        try {
            String pem = jwtProperties.getPrivateKeyPem();
            if (pem == null || pem.isBlank() || jwtProperties.getPublicKeyPem() == null || jwtProperties.getPublicKeyPem().isBlank()) {
                return ephemeralKeyPair().getPrivate();
            }
            byte[] decoded = Base64.getDecoder().decode(pem.getBytes(StandardCharsets.UTF_8));
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AppException("JWT_KEY_INVALID", "JWT private key format is invalid", "app.jwt.private-key-pem");
        }
    }

    private PublicKey resolvePublicKey() {
        try {
            String pem = jwtProperties.getPublicKeyPem();
            if (pem == null || pem.isBlank() || jwtProperties.getPrivateKeyPem() == null || jwtProperties.getPrivateKeyPem().isBlank()) {
                return ephemeralKeyPair().getPublic();
            }
            byte[] decoded = Base64.getDecoder().decode(pem.getBytes(StandardCharsets.UTF_8));
            X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AppException("JWT_KEY_INVALID", "JWT public key format is invalid", "app.jwt.public-key-pem");
        }
    }

    private synchronized KeyPair ephemeralKeyPair() {
        if (!jwtProperties.isAllowEphemeralKeyPair()) {
            throw new AppException(
                "JWT_KEY_MISSING",
                "JWT_PRIVATE_KEY_B64 and JWT_PUBLIC_KEY_B64 must be configured when ephemeral keys are disabled",
                "app.jwt.allow-ephemeral-key-pair"
            );
        }
        if (ephemeralKeyPair != null) {
            return ephemeralKeyPair;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            ephemeralKeyPair = generator.generateKeyPair();
            log.warn("JWT keys are not configured; generated ephemeral key pair for current runtime only.");
            return ephemeralKeyPair;
        } catch (Exception ex) {
            throw new AppException("JWT_KEY_INVALID", "Failed to initialize ephemeral JWT keys", null);
        }
    }
}
