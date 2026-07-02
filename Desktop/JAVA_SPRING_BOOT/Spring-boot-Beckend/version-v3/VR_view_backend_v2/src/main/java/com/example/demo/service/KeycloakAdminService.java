package com.example.demo.service;

import com.example.demo.exception.KeycloakAdminException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class KeycloakAdminService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${keycloak.base-url}")
    private String keycloakBaseUrl;

    @Value("${keycloak.realm}")
    private String realm;


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
    }

    public UserData getUserBySub(String sub, String accessToken) {
        String url = adminBaseUrl()
                + "/users/"
                + encode(sub);

        JsonNode user = getJson(url, accessToken);

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
        if (sessionId != null && !sessionId.isBlank()) {
            String url = adminBaseUrl()
                    + "/sessions/"
                    + encode(sessionId);

            exchangeWithoutBody(url, HttpMethod.DELETE, accessToken);
            return;
        }

        String url = adminBaseUrl()
                + "/users/"
                + encode(userId)
                + "/logout";

        exchangeWithoutBody(url, HttpMethod.POST, accessToken);
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
            throw convertKeycloakError(e);
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
                HttpStatus.NOT_FOUND,
                "client_not_found",
                "Keycloak client was not found"
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
            throw convertKeycloakError(e);

        } catch (Exception e) {
            throw new KeycloakAdminException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "keycloak_request_failed",
                    "Failed to call Keycloak Admin API"
            );
        }
    }


    private KeycloakAdminException convertKeycloakError(
            HttpStatusCodeException e
    ) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            return new KeycloakAdminException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "Requested Keycloak resource was not found"
            );
        }

        if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
            return new KeycloakAdminException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Token does not have Keycloak permission"
            );
        }

        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            return new KeycloakAdminException(
                    HttpStatus.UNAUTHORIZED,
                    "invalid_token",
                    "The authentication information required for logout is invalid."
            );
        }

        return new KeycloakAdminException(
                HttpStatus.BAD_GATEWAY,
                "keycloak_request_failed",
                "Keycloak Admin API request failed"
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
