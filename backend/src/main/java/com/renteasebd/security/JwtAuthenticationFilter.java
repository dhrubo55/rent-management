package com.renteasebd.security;

import com.renteasebd.common.AppException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import com.renteasebd.repository.RefreshTokenRepository;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final RefreshTokenRepository refreshTokens;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService,
                                   RefreshTokenRepository refreshTokens) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.refreshTokens = refreshTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            Claims claims = jwtService.parseClaims(token);
            String email = claims.get("email", String.class);
            String sessionId = claims.get("sid", String.class);

            if (email != null && sessionId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                if (userDetails instanceof UserPrincipal principal
                    && principal.isEnabled()
                    && String.valueOf(principal.id()).equals(claims.getSubject())
                    && refreshTokens.existsByIdAndUserId(sessionId, principal.id())) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (AppException | AuthenticationException ex) {
            // Keep request unauthenticated; secured routes will be rejected downstream.
        }

        filterChain.doFilter(request, response);
    }
}
