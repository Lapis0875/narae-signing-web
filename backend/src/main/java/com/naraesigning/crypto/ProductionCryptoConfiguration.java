package com.naraesigning.crypto;

import com.naraesigning.config.PlatformProperties;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("production")
class ProductionCryptoConfiguration {
    @Bean
    VersionedCryptoService versionedCryptoService(PlatformProperties properties) {
        if (properties.masterKeyFile() == null || properties.cryptoKeyVersion() == null) {
            throw new CryptoException();
        }
        return VersionedCryptoService.fromKeyFile(
                Path.of(properties.masterKeyFile()), properties.cryptoKeyVersion());
    }
}
