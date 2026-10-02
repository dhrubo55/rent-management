package com.renteasebd.auth;

import com.renteasebd.auth.dto.AuthResponse;
import com.renteasebd.auth.dto.AuthSessionResponse;
import com.renteasebd.auth.dto.ForgotPasswordRequest;
import com.renteasebd.auth.dto.LoginRequest;
import com.renteasebd.auth.dto.RefreshRequest;
import com.renteasebd.auth.dto.ResetPasswordRequest;
import com.renteasebd.common.AppException;
import com.renteasebd.common.AuditService;
import com.renteasebd.domain.auth.RefreshToken;
import com.renteasebd.domain.user.User;
import com.renteasebd.repository.RefreshTokenRepository;
import com.renteasebd.repository.UserRepository;
import com.renteasebd.security.JwtProperties;
import com.renteasebd.security.JwtService;
import com.renteasebd.security.UserPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int OTP_TTL_MINUTES = 10;

    private final AuthenticationManager authenticationManager;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, PasswordResetChallenge> resetChallenges = new ConcurrentHashMap<>();

    public AuthService(
        AuthenticationManager authenticationManager,
        RefreshTokenRepository refreshTokenRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        AuditService auditService,
        JwtService jwtService,
        JwtProperties jwtProperties
    ) {
        this.authenticationManager = authenticationManager;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String userAgent) {
        try {
            var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));
            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            User user = principal.domainUser();

            if (!user.isActive()) {
                throw new AppException("ACCOUNT_DISABLED", "Your account is disabled", null);
            }

            String refreshTokenPlain = UUID.randomUUID().toString();
            RefreshToken refreshToken = new RefreshToken();
            refreshToken.setUser(user);
            refreshToken.setTokenHash(hashToken(refreshTokenPlain));
            refreshToken.setDeviceHint(trimDeviceHint(userAgent));
            refreshToken.setCreatedAt(LocalDateTime.now());
            refreshToken.setExpiresAt(LocalDateTime.now().plus(jwtProperties.getRefreshTokenTtl()));
            refreshTokenRepository.save(refreshToken);

            auditService.log(user.getId(), "AUTH_LOGIN", "USER", String.valueOf(user.getId()), null,
                Map.of("deviceHint", refreshToken.getDeviceHint()));

            return new AuthResponse(
                jwtService.generateAccessToken(user, refreshToken.getId()),
                refreshTokenPlain,
                jwtProperties.getAccessTokenTtl().toSeconds(),
                normalizePreferredLanguage(user.getPreferredLanguage()),
                user.getRole().name());
        } catch (AuthenticationException ex) {
            userRepository.findByEmailIgnoreCase(normalizeEmail(request.email())).ifPresent(user ->
                auditService.log(user.getId(), "AUTH_LOGIN_FAILED", "USER", String.valueOf(user.getId()), null,
                    Map.of("reason", "INVALID_CREDENTIALS"))
            );
            throw new AppException("INVALID_CREDENTIALS", "Invalid email or password", "email");
        }
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hashToken(request.refreshToken()))
            .orElseThrow(() -> new AppException("TOKEN_INVALID", "Refresh token is invalid", "refreshToken"));

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(token);
            throw new AppException("TOKEN_EXPIRED", "Refresh token is expired", "refreshToken");
        }

        User user = token.getUser();
        if (!user.isActive()) {
            throw new AppException("ACCOUNT_DISABLED", "Your account is disabled", null);
        }

        String nextPlain = UUID.randomUUID().toString();
        RefreshToken next = new RefreshToken();
        next.setUser(user);
        next.setTokenHash(hashToken(nextPlain));
        next.setDeviceHint(token.getDeviceHint());
        next.setExpiresAt(LocalDateTime.now().plus(jwtProperties.getRefreshTokenTtl()));
        refreshTokenRepository.delete(token);
        refreshTokenRepository.save(next);
        auditService.log(user.getId(), "AUTH_REFRESH", "USER", String.valueOf(user.getId()), null,
            Map.of("refreshTokenId", next.getId()));

        return new AuthResponse(
            jwtService.generateAccessToken(user, next.getId()),
            nextPlain,
            jwtProperties.getAccessTokenTtl().toSeconds(),
            normalizePreferredLanguage(user.getPreferredLanguage()),
            user.getRole().name());
    }

    @Transactional
    public void updatePreferredLanguage(Long userId, String language) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException("UNAUTHORIZED", "Authentication required", null));
        String normalized = normalizePreferredLanguage(language);
        user.setPreferredLanguage(normalized);
        userRepository.save(user);
        auditService.log(userId, "AUTH_PREFERRED_LANGUAGE_UPDATED", "USER", String.valueOf(userId), null,
            Map.of("preferredLanguage", normalized));
    }

    @Transactional
    public void logout(String refreshTokenPlain, Long actorUserId) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hashToken(refreshTokenPlain))
            .orElseThrow(() -> new AppException("TOKEN_INVALID", "Refresh token is invalid", "refreshToken"));
        refreshTokenRepository.delete(token);

        auditService.log(actorUserId == null ? token.getUser().getId() : actorUserId,
            "AUTH_LOGOUT", "REFRESH_TOKEN", token.getId(), null, null);
    }

    @Transactional
    public void logoutAll(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
        auditService.log(userId, "AUTH_LOGOUT_ALL", "USER", String.valueOf(userId), null, null);
    }

    @Transactional(readOnly = true)
    public List<AuthSessionResponse> listSessions(Long userId) {
        return refreshTokenRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(token -> new AuthSessionResponse(
                token.getId(),
                token.getDeviceHint(),
                token.getCreatedAt(),
                token.getExpiresAt()
            ))
            .toList();
    }

    @Transactional
    public void revokeSession(Long userId, String sessionId) {
        int affected = refreshTokenRepository.deleteByIdAndUserId(sessionId, userId);
        if (affected == 0) {
            throw new AppException("SESSION_NOT_FOUND", "Session not found", "sessionId");
        }
        auditService.log(userId, "AUTH_SESSION_REVOKED", "REFRESH_TOKEN", sessionId, null, null);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (!user.isActive()) {
                return;
            }

            String otp = generateOtp();
            resetChallenges.put(email, new PasswordResetChallenge(otp, LocalDateTime.now().plusMinutes(OTP_TTL_MINUTES)));
            auditService.log(user.getId(), "AUTH_FORGOT_PASSWORD_REQUESTED", "USER", String.valueOf(user.getId()), null, null);
            log.info("Generated password reset OTP for {}. OTP: {}", email, otp);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new AppException("RESET_REQUEST_INVALID", "Reset request is invalid", "email"));

        PasswordResetChallenge challenge = resetChallenges.get(email);
        if (challenge == null) {
            throw new AppException("OTP_INVALID", "OTP is invalid or not requested", "otp");
        }

        if (challenge.expiresAt().isBefore(LocalDateTime.now())) {
            resetChallenges.remove(email);
            throw new AppException("OTP_EXPIRED", "OTP has expired, request a new one", "otp");
        }

        if (!challenge.otp().equals(request.otp().trim())) {
            throw new AppException("OTP_INVALID", "OTP is invalid or not requested", "otp");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenRepository.deleteByUserId(user.getId());
        resetChallenges.remove(email);

        auditService.log(user.getId(), "AUTH_PASSWORD_RESET", "USER", String.valueOf(user.getId()), null, null);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception ex) {
            throw new AppException("TOKEN_HASH_ERROR", "Failed to process token", "refreshToken");
        }
    }

    private String trimDeviceHint(String userAgent) {
        if (userAgent == null) {
            return "unknown";
        }
        return userAgent.length() <= 200 ? userAgent : userAgent.substring(0, 200);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String normalizePreferredLanguage(String language) {
        String normalized = language == null ? "en" : language.trim().toLowerCase();
        if (!"en".equals(normalized) && !"bn".equals(normalized)) {
            throw new AppException("INVALID_LANGUAGE", "Language must be en or bn", "language");
        }
        return normalized;
    }

    private String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private record PasswordResetChallenge(String otp, LocalDateTime expiresAt) {
    }
}
