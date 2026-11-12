package com.bizbitenow.printservice.security;

import com.bizbitenow.printservice.config.JwtProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;

@Component
public class ServiceApiKeyFilter extends OncePerRequestFilter {
    private final JwtProperties properties;
    private final ObjectMapper objectMapper;

    public ServiceApiKeyFilter(JwtProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Service-Key");
        if (isServiceRoute(request) || supplied != null) {
            String expected = properties.serviceApiKey();
            boolean valid = expected != null && !expected.isBlank() && supplied != null
                    && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8));
            if (!valid) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), Map.of(
                        "timestamp", Instant.now(), "status", 401, "code", "UNAUTHORIZED",
                        "message", "Authentication failed", "path", request.getRequestURI()));
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "service", null, AuthorityUtils.createAuthorityList("ROLE_SERVICE")));
        }
        chain.doFilter(request, response);
    }

    private boolean isServiceRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.equals("/api/v1/pairing-codes") && "POST".equals(method)) return true;
        if (path.equals("/api/v1/releases") && "POST".equals(method)) return true;
        if (path.equals("/api/v1/jobs") && ("POST".equals(method) || "GET".equals(method))) return true;
        if (path.equals("/api/v1/jobs/pending") || path.equals("/api/v1/jobs/history")) return false;
        if (path.matches("/api/v1/jobs/[^/]+") && "GET".equals(method)) return true;
        if (path.matches("/api/v1/jobs/[^/]+/(cancel|retry)") && "POST".equals(method)) return true;
        if (path.matches("/api/v1/sellers/[^/]+/devices") && "GET".equals(method)) return true;
        return path.matches("/api/v1/devices/[^/]+") && "DELETE".equals(method);
    }
}
