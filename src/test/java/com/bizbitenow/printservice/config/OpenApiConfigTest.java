package com.bizbitenow.printservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {
    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void publishesConfiguredProductionServerUrl() {
        OpenAPI openApi = config.printServiceApi("https://printer-backend-2.onrender.com");

        assertThat(openApi.getServers())
                .extracting(server -> server.getUrl())
                .containsExactly("https://printer-backend-2.onrender.com");
    }

    @Test
    void publishesConfiguredLocalServerUrl() {
        OpenAPI openApi = config.printServiceApi("http://localhost:8080");

        assertThat(openApi.getServers())
                .extracting(server -> server.getUrl())
                .containsExactly("http://localhost:8080");
    }
}
