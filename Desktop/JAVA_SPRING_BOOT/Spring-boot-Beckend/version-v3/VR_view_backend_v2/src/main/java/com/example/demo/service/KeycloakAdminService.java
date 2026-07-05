package com.example.demo.service;

import com.example.demo.exception.KeycloakAdminException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KeycloakAdminService {

    private static final String AUTH_INVALID_MESSAGE = "認証情報が不正です";
    private static final String LOGOUT_INVALID_MESSAGE = "ログアウトに必要な認証情報が不正です";
    private static final String CLIENT_ROLES_PERMISSION_MESSAGE = "ロール一覧を取得する権限がありません";
    private static final String USER_INFO_PERMISSION_MESSAGE = "ユーザー情報を取得する権限がありません";
    private static final String USER_NOT_FOUND_MESSAGE = "対象ユーザーが見つかりません";
    private static final long DEFAULT_USED_REFRESH_TOKEN_TTL_SECONDS = 86_400L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, Instant> usedRefreshTokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> usedAccessTokens = new ConcurrentHashMap<>();

    @Value("${keycloak.base-url}")
    private String keycloakBaseUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.app-client-id}")
    private String applicationClientId;

    @Value("${keycloak.client-secret:}")
    private String clientSecret;

    @Value("${keycloak.admin-client-id:${keycloak.app-client-id}}")
    private String adminClientId;

    @Value("${keycloak.admin-client-secret:${keycloak.client-secret:}}")
    private String adminClientSecret;


    @Autowired
    public KeycloakAdminService(
            ObjectMapper objectMapper
    ) {
        this(objectMapper, new RestTemplate());
    }

    KeycloakAdminService(
            ObjectMapper objectMapper,
            RestTemplate restTemplate
    ) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    public List<RoleData> getClientRoles(String clientId, String accessToken) {
        try {
            JsonNode client = findClientByClientId(
                    clientId,
                    accessToken
            );

            String internalClientId = client.path("id").asText();

            String url = adminBaseUrl()
                    + "/clients/"
                    + encode(internalClientId)
                    + "/roles";

            JsonNode roles = getJson(url, accessToken);

            List<RoleData> result = new ArrayList<>();

            for (JsonNode role : roles) {
                result.add(new RoleData(
                        role.path("id").asText(),
                        role.path("name").asText(),
                        role.path("description").isNull()
                                ? null
                                : role.path("description").asText(null)
                ));
            }

            return result;
        } catch (KeycloakAdminException e) {
            if ("not_found".equals(e.getErrorCode())
                    || "client_not_found".equals(e.getErrorCode())
                    || "permission_denied".equals(e.getErrorCode())) {
                throw clientRolesPermissionException();
            }

            throw e;
        }
    }

    public UserData getUserBySub(String sub, String accessToken) {
        String url = adminBaseUrl()
                + "/users/"
                + encode(sub);

        JsonNode user;

        try {
            user = getJson(url, accessToken);
        } catch (KeycloakAdminException e) {
            if ("not_found".equals(e.getErrorCode())) {
                throw userNotFoundException();
            }

            if ("permission_denied".equals(e.getErrorCode())) {
                throw userInfoPermissionException();
            }

            throw e;
        }

        String firstName = user.path("firstName").asText("");
        String lastName = user.path("lastName").asText("");

        String fullName = (firstName + " " + lastName).trim();

        if (fullName.isBlank()) {
            fullName = user.path("username").asText(null);
        }

        return new UserData(
                user.path("id").asText(),
                user.path("id").asText(),
                fullName,
                user.path("username").asText(null),
                user.path("email").asText(null)
        );
    }

    public void logoutCurrentSession(
            String userId,
            String sessionId,
            String accessToken
    ) {
        JsonNode sessions = getUserSessionsForLogout(userId, accessToken);

        if (sessionId != null && !sessionId.isBlank()) {
            if (!hasSession(sessions, sessionId)) {
                throw userLoggedOutException();
            }

            String url = adminBaseUrl()
                    + "/sessions/"
                    + encode(sessionId);

            exchangeWithoutBody(url, HttpMethod.DELETE, accessToken);
            return;
        }

        if (!sessions.elements().hasNext()) {
            throw userLoggedOutException();
        }

        String url = adminBaseUrl()
                + "/users/"
                + encode(userId)
                + "/logout";

        exchangeWithoutBody(url, HttpMethod.POST, accessToken);
    }

    public void logoutCurrentSessionWithServiceAccount(
            String userId,
            String sessionId,
            String accessToken,
            Instant accessTokenExpiresAt
    ) {
        rejectAlreadyUsedAccessToken(accessToken);

        logoutCurrentSession(
                userId,
                sessionId,
                getServiceAccountAccessToken()
        );

        rememberUsedAccessToken(accessToken, accessTokenExpiresAt);
    }

    public void logoutWithRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidLogoutTokenException();
        }

        rejectAlreadyUsedRefreshToken(refreshToken);

        String url = keycloakBaseUrl
                + "/realms/"
                + encode(realm)
                + "/protocol/openid-connect/logout";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", applicationClientId);
        form.add("refresh_token", refreshToken);

        if (clientSecret != null && !clientSecret.isBlank()) {
            form.add("client_secret", clientSecret);
        }

        try {
            restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    Void.class
            );
            rememberUsedRefreshToken(refreshToken);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST
                    || e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw invalidLogoutTokenException();
            }

            throw convertKeycloakError(e, LOGOUT_INVALID_MESSAGE);
        }
    }

    private void rejectAlreadyUsedRefreshToken(String refreshToken) {
        removeExpiredUsedRefreshTokens();

        if (usedRefreshTokens.containsKey(tokenHash(refreshToken))) {
            throw userLoggedOutException();
        }
    }

    private void rejectAlreadyUsedAccessToken(String accessToken) {
        removeExpiredUsedAccessTokens();

        if (usedAccessTokens.containsKey(tokenHash(accessToken))) {
            throw userLoggedOutException();
        }
    }

    private String getServiceAccountAccessToken() {
        if (adminClientSecret == null || adminClientSecret.isBlank()) {
            throw invalidLogoutTokenException();
        }

        String url = keycloakBaseUrl
                + "/realms/"
                + encode(realm)
                + "/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", adminClientId);
        form.add("client_secret", adminClientSecret);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());
            String accessToken = json.path("access_token").asText(null);

            if (accessToken == null || accessToken.isBlank()) {
                throw invalidLogoutTokenException();
            }

            return accessToken;
        } catch (KeycloakAdminException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            throw invalidLogoutTokenException();
        } catch (Exception e) {
            throw new KeycloakAdminException(
                    HttpStatus.BAD_GATEWAY,
                    "keycloak_request_failed",
                    "Keycloak Admin API request failed"
            );
        }
    }

    private void rememberUsedRefreshToken(String refreshToken) {
        usedRefreshTokens.put(
                tokenHash(refreshToken),
                refreshTokenExpiresAt(refreshToken)
        );
    }

    private void removeExpiredUsedRefreshTokens() {
        Instant now = Instant.now();
        usedRefreshTokens.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private void rememberUsedAccessToken(
            String accessToken,
            Instant expiresAt
    ) {
        usedAccessTokens.put(
                tokenHash(accessToken),
                expiresAt == null
                        ? Instant.now().plusSeconds(DEFAULT_USED_REFRESH_TOKEN_TTL_SECONDS)
                        : expiresAt
        );
    }

    private void removeExpiredUsedAccessTokens() {
        Instant now = Instant.now();
        usedAccessTokens.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private Instant refreshTokenExpiresAt(String refreshToken) {
        String[] parts = refreshToken.split("\\.");

        if (parts.length < 2) {
            return Instant.now().plusSeconds(DEFAULT_USED_REFRESH_TOKEN_TTL_SECONDS);
        }

        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8
            );

            JsonNode json = objectMapper.readTree(payload);
            long expiresAtEpochSecond = json.path("exp").asLong(0L);

            if (expiresAtEpochSecond > 0L) {
                return Instant.ofEpochSecond(expiresAtEpochSecond);
            }
        } catch (Exception ignored) {
            return Instant.now().plusSeconds(DEFAULT_USED_REFRESH_TOKEN_TTL_SECONDS);
        }

        return Instant.now().plusSeconds(DEFAULT_USED_REFRESH_TOKEN_TTL_SECONDS);
    }

    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private JsonNode getUserSessionsForLogout(
            String userId,
            String accessToken
    ) {
        String url = adminBaseUrl()
                + "/users/"
                + encode(userId)
                + "/sessions";

        try {
            return getJson(url, accessToken);
        } catch (KeycloakAdminException e) {
            if ("invalid_token".equals(e.getErrorCode())) {
                throw userLoggedOutException();
            }

            throw e;
        }
    }

    private boolean hasSession(
            JsonNode sessions,
            String sessionId
    ) {
        for (JsonNode session : sessions) {
            if (sessionId.equals(session.path("id").asText())) {
                return true;
            }
        }

        return false;
    }

    private KeycloakAdminException userLoggedOutException() {
        return new KeycloakAdminException(
                HttpStatus.UNAUTHORIZED,
                "user_loggedout",
                "ログインしていません"
        );
    }

    private KeycloakAdminException invalidLogoutTokenException() {
        return new KeycloakAdminException(
                HttpStatus.UNAUTHORIZED,
                "invalid_token",
                LOGOUT_INVALID_MESSAGE
        );
    }

    private void exchangeWithoutBody(
            String url,
            HttpMethod method,
            String accessToken
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        try {
            restTemplate.exchange(
                    url,
                    method,
                    new HttpEntity<>(headers),
                    Void.class
            );
        } catch (HttpStatusCodeException e) {
            throw convertKeycloakError(e, LOGOUT_INVALID_MESSAGE);
        }
    }

    private JsonNode findClientByClientId(
            String clientId,
            String accessToken
    ) {
        String url = adminBaseUrl()
                + "/clients?clientId="
                + encode(clientId);

        JsonNode clients = getJson(url, accessToken);

        for (JsonNode client : clients) {
            if (clientId.equals(client.path("clientId").asText())) {
                return client;
            }
        }

        throw new KeycloakAdminException(
                HttpStatus.FORBIDDEN,
                "permission_denied",
                CLIENT_ROLES_PERMISSION_MESSAGE
        );
    }

    /**
     * Perform a GET request to the Keycloak Admin API and return parsed JSON.
     */
    private JsonNode getJson(
            String url,
            String accessToken
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            return objectMapper.readTree(response.getBody());

        } catch (HttpStatusCodeException e) {
            throw convertKeycloakError(e, AUTH_INVALID_MESSAGE);

        } catch (Exception e) {
            throw new KeycloakAdminException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "keycloak_request_failed",
                    "Keycloak管理APIの呼び出しに失敗しました"
            );
        }
    }


    private KeycloakAdminException convertKeycloakError(
            HttpStatusCodeException e
    ) {
        return convertKeycloakError(e, AUTH_INVALID_MESSAGE);
    }

    private KeycloakAdminException convertKeycloakError(
            HttpStatusCodeException e,
            String invalidTokenMessage
    ) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            return new KeycloakAdminException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "要求されたKeycloakリソースが見つかりません"
            );
        }

        if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
            return new KeycloakAdminException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Keycloakの権限がありません"
            );
        }

        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            return new KeycloakAdminException(
                    HttpStatus.UNAUTHORIZED,
                    "invalid_token",
                    invalidTokenMessage
            );
        }

        return new KeycloakAdminException(
                HttpStatus.BAD_GATEWAY,
                "keycloak_request_failed",
                "Keycloak管理APIのリクエストに失敗しました"
        );
    }

    private KeycloakAdminException clientRolesPermissionException() {
        return new KeycloakAdminException(
                HttpStatus.FORBIDDEN,
                "permission_denied",
                CLIENT_ROLES_PERMISSION_MESSAGE
        );
    }

    private KeycloakAdminException userNotFoundException() {
        return new KeycloakAdminException(
                HttpStatus.NOT_FOUND,
                "not_found",
                USER_NOT_FOUND_MESSAGE
        );
    }

    private KeycloakAdminException userInfoPermissionException() {
        return new KeycloakAdminException(
                HttpStatus.FORBIDDEN,
                "permission_denied",
                USER_INFO_PERMISSION_MESSAGE
        );
    }

    private String adminBaseUrl() {
        return keycloakBaseUrl
                + "/admin/realms/"
                + realm;
    }

    private String encode(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }

    public record RoleData(
            String roleId,
            String roleName,
            String description
    ) {
    }

    public record UserData(
            String userId,
            String sub,
            String userName,
            String loginId,
            String email
    ) {
    }
}
