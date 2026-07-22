package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.exception.KeycloakAdminException;
import com.example.demo.service.FacilitiesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/facilities")
public class FacilitiesController {

    private final FacilitiesService facilitiesService;

    public FacilitiesController(FacilitiesService facilitiesService) {
        this.facilitiesService = facilitiesService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listFacilities(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        if (parentId != null && keyword != null) {
            throw invalidRequest();
        }

        validateKeyword(keyword);

        return ResponseEntity.ok(
                ApiResponse.ok(facilitiesService.listFacilities(parseOptionalFacilityId(parentId), keyword))
        );
    }

    @GetMapping("/{facilityId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFacility(
            @PathVariable String facilityId
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(facilitiesService.getFacility(parseFacilityId(facilityId)))
        );
    }

    @GetMapping("/{facilityId}/view-html")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getViewHtml(
            @PathVariable String facilityId
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(facilitiesService.getViewHtml(parseFacilityId(facilityId)))
        );
    }

    @GetMapping("/{facilityId}/{subPath}")
    public ResponseEntity<ApiResponse<Void>> getUnsupportedFacilitySubPath(
            @PathVariable String facilityId,
            @PathVariable String subPath
    ) {
        parseFacilityId(facilityId);
        throw invalidRequest();
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createFacility(
            @RequestBody Map<String, Object> request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(facilitiesService.createFacility(
                        request,
                        extractRoleNames(authentication)
                ))
        );
    }

    @PutMapping("/{facilityId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateFacility(
            @PathVariable String facilityId,
            @RequestBody Map<String, Object> request
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(facilitiesService.updateFacility(parseFacilityId(facilityId), request))
        );
    }

    @DeleteMapping("/{facilityId}")
    public ResponseEntity<ApiResponse<Void>> deleteFacility(
            @PathVariable String facilityId
    ) {
        facilitiesService.deleteFacility(parseFacilityId(facilityId));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{facilityId}/move")
    public ResponseEntity<ApiResponse<Map<String, Object>>> moveFacility(
            @PathVariable String facilityId,
            @RequestBody Map<String, Object> request
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(facilitiesService.moveFacility(parseFacilityId(facilityId), request))
        );
    }

    @PostMapping("/{facilityId}/copy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> copyFacility(
            @PathVariable String facilityId,
            @RequestBody Map<String, Object> request
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(facilitiesService.copyFacility(parseFacilityId(facilityId), request))
        );
    }

    @GetMapping("/{facilityId}/vr-view")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getVrView(
            @PathVariable String facilityId
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(facilitiesService.getVrView(parseFacilityId(facilityId)))
        );
    }

    private Long parseFacilityId(String facilityId) {
        if (facilityId == null
                || facilityId.isBlank()
                || facilityId.getBytes(StandardCharsets.UTF_8).length > 19
                || !facilityId.matches("\\d+")) {
            throw invalidRequest();
        }

        try {
            return Long.parseLong(facilityId);
        } catch (NumberFormatException exception) {
            throw invalidRequest();
        }
    }

    private Long parseOptionalFacilityId(String facilityId) {
        if (facilityId == null) {
            return null;
        }

        return parseFacilityId(facilityId);
    }

    private void validateKeyword(String keyword) {
        if (keyword == null) {
            return;
        }

        if (keyword.isBlank()
                || keyword.getBytes(StandardCharsets.UTF_8).length >= 128
                || keyword.chars().anyMatch(Character::isISOControl)) {
            throw invalidRequest();
        }
    }

    private KeycloakAdminException invalidRequest() {
        return new KeycloakAdminException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "リクエストパラメータが不正です"
        );
    }

    private Set<String> extractRoleNames(Authentication authentication) {
        if (authentication == null) {
            return Set.of();
        }

        return authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_")
                        ? authority.substring("ROLE_".length())
                        : authority)
                .collect(Collectors.toSet());
    }
}
