package com.example.demo.service;

import com.example.demo.exception.KeycloakAdminException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeycloakAdminServiceTest {

    @Test
    void unauthorizedKeycloakResponseShouldMapToInvalidTokenError() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        KeycloakAdminService service = new KeycloakAdminService(objectMapper, restTemplate);

        HttpStatusCodeException exception = new TestHttpStatusCodeException();

        when(restTemplate.exchange(any(String.class), eq(org.springframework.http.HttpMethod.DELETE), any(), eq(Void.class)))
                .thenThrow(exception);

        assertThatThrownBy(() -> service.logoutCurrentSession("user-id", "session-id", "token"))
                .isInstanceOf(KeycloakAdminException.class)
                .hasMessage("The authentication information required for logout is invalid.")
                .extracting("errorCode")
                .isEqualTo("invalid_token");
    }

    private static class TestHttpStatusCodeException extends HttpStatusCodeException {
        private TestHttpStatusCodeException() {
            super(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
    }
}
