package com.example.demo.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

public class ApiResponseSerializer extends StdSerializer<ApiResponse<?>> {

    @SuppressWarnings("unchecked")
    public ApiResponseSerializer() {
        super((Class<ApiResponse<?>>) (Class<?>) ApiResponse.class);
    }

    @Override
    public void serialize(ApiResponse<?> value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        gen.writeBooleanField("success", value.success());
        gen.writeStringField("error_code", value.error_code());
        gen.writeStringField("message", value.message());

        if (!value.success()) {
            gen.writeFieldName("errors");
            gen.writeNull();
        }

        gen.writeFieldName("data");
        gen.writeObject(value.data());
        gen.writeEndObject();
    }
}
