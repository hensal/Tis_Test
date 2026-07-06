package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CurrentUserResponseService {

    private final KeycloakAdminService keycloakAdminService;

    @Value("${keycloak.app-client-id}")
    private String applicationClientId;

    public CurrentUserResponseService(KeycloakAdminService keycloakAdminService) {
        this.keycloakAdminService = keycloakAdminService;
    }

    public Map<String, Object> buildCurrentUserData(Jwt jwt) {
        List<String> roles = extractRoles(jwt);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", jwt.getSubject());
        data.put("login_id", defaultValue(jwt.getClaimAsString("preferred_username")));
        data.put("user_name", resolveUserName(jwt));
        data.put("roles", roles);
        data.put("can_use_admin_mode", canUseAdminMode(roles));
        data.put(
                "created_at",
                keycloakAdminService.getUserCreatedAtWithServiceAccount(
                        jwt.getSubject()
                )
        );

        return data;
    }

    private String resolveUserName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");

        if (name != null && !name.isBlank()) {
            return name;
        }

        String givenName = defaultValue(jwt.getClaimAsString("given_name"));
        String familyName = defaultValue(jwt.getClaimAsString("family_name"));
        String fullName = (givenName + " " + familyName).trim();

        if (!fullName.isBlank()) {
            return fullName;
        }

        return defaultValue(jwt.getClaimAsString("preferred_username"));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        roles.addAll(extractRolesFromAccessMap(realmAccess));

        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");

        if (resourceAccess != null) {
            roles.addAll(extractClientRoles(resourceAccess, applicationClientId));
            roles.addAll(extractClientRoles(resourceAccess, "realm-management"));
        }

        return new ArrayList<>(roles);
    }

    private List<String> extractClientRoles(
            Map<String, Object> resourceAccess,
            String clientId
    ) {
        Object client = resourceAccess.get(clientId);

        if (!(client instanceof Map<?, ?> clientAccess)) {
            return List.of();
        }

        return extractRolesFromAccessMap(clientAccess);
    }

    private List<String> extractRolesFromAccessMap(Map<?, ?> accessMap) {
        if (accessMap == null) {
            return List.of();
        }

        Object rawRoles = accessMap.get("roles");

        if (!(rawRoles instanceof List<?> roleList)) {
            return List.of();
        }

        return roleList.stream()
                .map(Object::toString)
                .toList();
    }

    private boolean canUseAdminMode(List<String> roles) {
        return roles.stream()
                .anyMatch(role ->
                        "SYS_ADMIN".equals(role)
                                || "SYS_MASTER".equals(role)
                                || "User_admin".equals(role)
                );
    }

    private String defaultValue(String value) {
        return value == null ? "" : value;
    }
}
