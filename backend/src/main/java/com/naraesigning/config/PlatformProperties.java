package com.naraesigning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record PlatformProperties(
        String publicOrigin,
        String minioEndpoint,
        String minioBucket,
        String masterKeyFile,
        Integer cryptoKeyVersion) {}
