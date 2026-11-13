package com.bizbitenow.printservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI printServiceApi(@Value("${app.openapi.server-url}") String serverUrl) {
        return new OpenAPI().info(new Info().title("BizBiteNow Print Service").version("v1"))
                .servers(List.of(new Server().url(serverUrl)))
                .components(new Components().addSecuritySchemes("deviceBearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("serviceKey",
                                new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER).name("X-Service-Key")));
    }
}
