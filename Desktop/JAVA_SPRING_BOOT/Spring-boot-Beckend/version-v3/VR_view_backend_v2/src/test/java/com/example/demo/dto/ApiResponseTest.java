package com.example.demo.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void okResponseShouldOmitErrorsField() throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(ApiResponse.ok("done"));

        assertThat(json).contains("\"success\":true");
        assertThat(json).doesNotContain("\"errors\"");
    }

    @Test
    void errorResponseShouldIncludeErrorsField() throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(ApiResponse.error("ERR_001", "Something went wrong"));

        assertThat(json).contains("\"success\":false");
        assertThat(json).contains("\"errors\"");
    }
}
