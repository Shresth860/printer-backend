package com.bizbitenow.printservice.config;

import com.bizbitenow.printservice.security.JwtAuthFilter;
import com.bizbitenow.printservice.security.ServiceApiKeyFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter,
            ServiceApiKeyFilter apiKeyFilter,
            ObjectMapper objectMapper
    ) throws Exception {

        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(request, response, objectMapper,
                                        HttpStatus.UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "Authentication required"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(request, response, objectMapper,
                                        HttpStatus.FORBIDDEN,
                                        "FORBIDDEN",
                                        "Access denied")))
                .authorizeHttpRequests(auth -> auth

                        // Public endpoints
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/api/v1/app/latest-release",
                                "/actuator/health",
                                "/actuator/info",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()

                        // Service authentication
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/pairing-codes",
                                "/api/v1/releases"
                        ).hasRole("SERVICE")

                        // Device endpoints
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/jobs/pending",
                                "/api/v1/jobs/history"
                        ).hasAuthority("ROLE_DEVICE")

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/jobs/*/status"
                        ).hasAuthority("ROLE_DEVICE")

                        .requestMatchers(
                                "/api/v1/devices/me/**"
                        ).hasAuthority("ROLE_DEVICE")

                        // Service endpoints
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/jobs",
                                "/api/v1/jobs/**",
                                "/api/v1/sellers/*/devices"
                        ).hasAuthority("ROLE_SERVICE")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/jobs/**"
                        ).hasAuthority("ROLE_SERVICE")

                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/v1/devices/**"
                        ).hasAuthority("ROLE_SERVICE")

                        // Deny all unmatched requests
                        .anyRequest().denyAll()
                )
                .addFilterBefore(
                        apiKeyFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(
                        jwtAuthFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") String originList
    ) {

        List<String> origins = Arrays.stream(originList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return request -> {
            CorsConfiguration configuration = new CorsConfiguration();

            if (isDeviceEndpoint(request)) {
                return configuration;
            }

            configuration.setAllowedOrigins(origins);
            configuration.setAllowedMethods(
                    List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            );
            configuration.setAllowedHeaders(
                    List.of("Authorization", "Content-Type", "X-Service-Key")
            );
            configuration.setAllowCredentials(false);

            return configuration;
        };
    }

    private boolean isDeviceEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();

        return path.contains("/devices/me")
                || path.endsWith("/jobs/pending")
                || path.endsWith("/jobs/history");
    }

    private void writeError(
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            ObjectMapper mapper,
            HttpStatus status,
            String code,
            String message
    ) throws java.io.IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        mapper.writeValue(
                response.getOutputStream(),
                Map.of(
                        "timestamp", Instant.now(),
                        "status", status.value(),
                        "code", code,
                        "message", message,
                        "path", request.getRequestURI()
                )
        );
    }
}
