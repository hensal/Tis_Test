package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.service.KeycloakAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/keycloak")
public class KeycloakController {

    private final KeycloakAdminService keycloakAdminService;

    public KeycloakController(
            KeycloakAdminService keycloakAdminService
    ) {
        this.keycloakAdminService = keycloakAdminService;
    }

    /*
     * Logout the currently authenticated user's own Keycloak session.
     *
     * Caller sends:
     * - Authorization: Bearer ACCESS_TOKEN
     *
     * This uses the session id from the validated JWT when present.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader("Authorization") String authorizationHeader,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String accessToken = extractBearerToken(authorizationHeader);

        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required");
        }

        keycloakAdminService.logoutCurrentSession(
                jwt.getSubject(),
                getFirstAvailableClaim(jwt, "sid", "session_state"),
                accessToken
        );

        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /*
     * Return every role configured for the requested Keycloak client.
     *
     * Example:
     * GET /api/keycloak/client-roles?client_id=pygmalion_viewer
     */
    @GetMapping("/client-roles")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getClientRoles(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestParam("client_id") String clientId,
            @RequestParam(value = "role_name", required = false) String roleName
    ) {
        String accessToken = extractBearerToken(authorizationHeader);

        List<Map<String, String>> roles =
                keycloakAdminService.getClientRoles(clientId, accessToken)
                        .stream()
                        .filter(role ->
                                roleName == null
                                        || roleName.isBlank()
                                        || role.roleName().contains(roleName)
                        )
                        .map(role -> {
                            Map<String, String> data = new LinkedHashMap<>();
                            data.put("role_id", role.roleId());
                            data.put("role_name", role.roleName());
                            data.put(
                                    "description",
                                    role.description() == null
                                            ? ""
                                            : role.description()
                            );
                            return data;
                        })
                        .toList();

        return ResponseEntity.ok(ApiResponse.ok(roles));
    }

    /*
     * Return a Keycloak user by sub.
     *
     * Example:
     * GET /api/keycloak/user-info?sub=KEYCLOAK_USER_UUID
     */
    @GetMapping("/user-info")
    public ResponseEntity<ApiResponse<Map<String, String>>> getUserInfo(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestParam("sub") String sub
    ) {
        String accessToken = extractBearerToken(authorizationHeader);
        KeycloakAdminService.UserData user =
                keycloakAdminService.getUserBySub(sub, accessToken);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("user_id", user.userId());
        data.put("sub", user.sub());
        data.put("user_name", user.userName() == null ? "" : user.userName());
        data.put("login_id", user.loginId() == null ? "" : user.loginId());
        data.put("email", user.email() == null ? "" : user.email());

        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /*
     * Optional API: current user information from JWT.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, String>>> me(
            @AuthenticationPrincipal Jwt jwt
    ) {
        Map<String, String> data = Map.of(
                "user_id", jwt.getSubject(),
                "username",
                defaultValue(jwt.getClaimAsString("preferred_username")),
                "email",
                defaultValue(jwt.getClaimAsString("email")),
                "name",
                defaultValue(jwt.getClaimAsString("name"))
        );

        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    private String defaultValue(String value) {
        return value == null ? "" : value;
    }

    private String getFirstAvailableClaim(
            Jwt jwt,
            String... claimNames
    ) {
        for (String claimName : claimNames) {
            String value = jwt.getClaimAsString(claimName);

            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("Authorization header is required");
        }

        if (!authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Authorization header must be Bearer token");
        }

        return authorizationHeader.substring(7).trim();
    }
}
