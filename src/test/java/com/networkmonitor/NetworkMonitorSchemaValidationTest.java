package com.networkmonitor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NetworkMonitorSchemaValidationTest {

    @Test
    void validateJsonSchema_passesForValidPayload() throws Exception {
        NetworkMonitor monitor = new NetworkMonitor(null);

        String schema = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                + "\"type\":\"object\","
                + "\"properties\":{\"id\":{\"type\":\"integer\"}},"
                + "\"required\":[\"id\"]}";

        Path schemaFile = Files.createTempFile("schema-valid", ".json");
        Files.write(schemaFile, schema.getBytes(StandardCharsets.UTF_8));

        String payload = "{\"id\":123}";

        assertDoesNotThrow(() -> monitor.validateJsonSchema(payload, schemaFile.toString()));
    }

    @Test
    void validateJsonSchema_failsForInvalidPayload() throws Exception {
        NetworkMonitor monitor = new NetworkMonitor(null);

        String schema = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                + "\"type\":\"object\","
                + "\"properties\":{\"id\":{\"type\":\"integer\"}},"
                + "\"required\":[\"id\"]}";

        Path schemaFile = Files.createTempFile("schema-invalid", ".json");
        Files.write(schemaFile, schema.getBytes(StandardCharsets.UTF_8));

        String payload = "{\"id\":\"abc\"}";

        assertThrows(AssertionError.class,
                () -> monitor.validateJsonSchema(payload, schemaFile.toString()));
    }
}

