package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.service.CurrentUserResponseService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MeController {

    private final CurrentUserResponseService currentUserResponseService;

    public MeController(CurrentUserResponseService currentUserResponseService) {
        this.currentUserResponseService = currentUserResponseService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(currentUserResponseService.buildCurrentUserData(jwt))
        );
    }
}
