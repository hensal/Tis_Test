package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.dto.LogoutRequest;
import com.example.demo.service.CurrentUserResponseService;
import com.example.demo.service.KeycloakAdminService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final CurrentUserResponseService currentUserResponseService;

    public KeycloakController(
            KeycloakAdminService keycloakAdminService,
            CurrentUserResponseService currentUserResponseService
    ) {
        this.keycloakAdminService = keycloakAdminService;
        this.currentUserResponseService = currentUserResponseService;
    }

    /*
     * Logout the currently authenticated user's own Keycloak session.
     *
     * Caller sends:
     * - Authorization: Bearer ACCESS_TOKEN
     * - Optional refresh_token in the JSON or form body
     *
     * Access-token logout uses the backend's Keycloak client internally, so
     * normal users do not need realm-management/manage-users.
     */
    @PostMapping(
            value = "/logout",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Void>> logoutJson(
            @RequestHeader("Authorization") String authorizationHeader,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) LogoutRequest logoutRequest
    ) {
        return logout(
                authorizationHeader,
                jwt,
                logoutRequest == null ? null : logoutRequest.getRefresh_token()
        );
    }

    @PostMapping(
            value = "/logout",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public ResponseEntity<ApiResponse<Void>> logoutForm(
            @RequestHeader("Authorization") String authorizationHeader,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "refresh_token", required = false) String refreshToken
    ) {
        return logout(authorizationHeader, jwt, refreshToken);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logoutWithoutBody(
            @RequestHeader("Authorization") String authorizationHeader,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return logout(authorizationHeader, jwt, null);
    }

    private ResponseEntity<ApiResponse<Void>> logout(
            String authorizationHeader,
            Jwt jwt,
            String refreshToken
    ) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required");
        }

        if (refreshToken == null || refreshToken.isBlank()) {
            keycloakAdminService.logoutCurrentSessionWithServiceAccount(
                    jwt.getSubject(),
                    getFirstAvailableClaim(jwt, "sid", "session_state"),
                    extractBearerToken(authorizationHeader),
                    jwt.getExpiresAt()
            );
        } else {
            keycloakAdminService.logoutWithRefreshToken(refreshToken);
        }

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
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(currentUserResponseService.buildCurrentUserData(jwt))
        );
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
