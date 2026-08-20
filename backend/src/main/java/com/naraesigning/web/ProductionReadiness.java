package com.naraesigning.web;

import com.naraesigning.config.PlatformProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
final class ProductionReadiness {
    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final PlatformProperties properties;

    ProductionReadiness(JdbcTemplate jdbc, MinioClient minio, PlatformProperties properties) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.properties = properties;
    }

    boolean ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return minio.bucketExists(BucketExistsArgs.builder().bucket(properties.minioBucket()).build());
        } catch (Exception exception) {
            return false;
        }
    }
}
