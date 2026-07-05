package com.example.demo.config;

import com.example.demo.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${keycloak.app-client-id}")
    private String applicationClientId;

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .cors(cors -> cors.configurationSource(
                        corsConfigurationSource()
                ))
                .csrf(csrf -> csrf.disable())

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                (request, response, exceptionValue) ->
                                        writeError(
                                                response,
                                                HttpServletResponse.SC_UNAUTHORIZED,
                                                "invalid_token",
                                                authenticationErrorMessage(
                                                        request.getRequestURI()
                                                )
                                        )
                        )
                        .accessDeniedHandler(
                                (request, response, exceptionValue) ->
                                        writeError(
                                                response,
                                                HttpServletResponse.SC_FORBIDDEN,
                                                "permission_denied",
                                                accessDeniedMessage(
                                                        request.getRequestURI()
                                                )
                                        )
                        )
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/**"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/keycloak/logout"
                        ).authenticated()

                        .requestMatchers(
                                "/api/keycloak/me",
                                "/me"
                        ).authenticated()

                        .requestMatchers(
                                "/api/keycloak/client-roles"
                        ).hasAnyAuthority(
                                "ROLE_view-clients",
                                "ROLE_query-clients",
                                "ROLE_manage-clients",
                                "ROLE_admin",
                                "ROLE_ADMIN"
                        )

                        .requestMatchers(
                                "/api/keycloak/user-info"
                        ).hasAnyAuthority(
                                "ROLE_view-users",
                                "ROLE_query-users",
                                "ROLE_manage-users",
                                "ROLE_admin",
                                "ROLE_ADMIN"
                        )

                        .anyRequest().permitAll()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                jwtAuthenticationConverter()
                        ))
                );

        return http.build();
    }

    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(
                this::extractClientRoles
        );

        return converter;
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractClientRoles(Jwt jwt) {
        Map<String, Object> resourceAccess =
                jwt.getClaimAsMap("resource_access");

        if (resourceAccess == null) {
            return List.of();
        }

        Set<String> roles = new LinkedHashSet<>();
        roles.addAll(extractRolesForClient(resourceAccess, applicationClientId));
        roles.addAll(extractRolesForClient(resourceAccess, "realm-management"));

        return roles.stream()
                .map(role -> "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    private List<String> extractRolesForClient(
            Map<String, Object> resourceAccess,
            String clientId
    ) {
        Object client =
                resourceAccess.get(clientId);

        if (!(client instanceof Map<?, ?> clientAccess)) {
            return List.of();
        }

        Object rawRoles = clientAccess.get("roles");

        if (!(rawRoles instanceof List<?> roleList)) {
            return List.of();
        }

        return new ArrayList<>(roleList).stream()
                .map(Object::toString)
                .collect(Collectors.toList());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "http://localhost:5174"
        ));

        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "DELETE",
                "OPTIONS"
        ));

        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type"
        ));

        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/api/**",
                configuration
        );

        return source;
    }

    private void writeError(
            HttpServletResponse response,
            int status,
            String errorCode,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(
                MediaType.APPLICATION_JSON_VALUE
        );

        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.error(errorCode, message)
        );
    }

    private String authenticationErrorMessage(String requestUri) {
        if (requestUri != null && requestUri.endsWith("/logout")) {
            return "ログアウトに必要な認証情報が不正です";
        }

        return "認証情報が不正です";
    }

    private String accessDeniedMessage(String requestUri) {
        if (requestUri != null && requestUri.endsWith("/client-roles")) {
            return "ロール一覧を取得する権限がありません";
        }

        if (requestUri != null && requestUri.endsWith("/user-info")) {
            return "ユーザー情報を取得する権限がありません";
        }

        return "権限がありません";
    }
}
