package dev.ngb.mailer.boot.config;

import dev.ngb.mailer.boot.config.MailerSettings.Secrets;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.spring.security.AesGcmSecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The master key for credentials at rest. {@code AesGcmSecretCipher} refuses any key that does not decode to exactly 32
 * bytes and reports the property names an operator can grep for, so a misconfigured deployment fails at startup rather
 * than at the first tenant send.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailerSettings.class)
class MailerSecretCipherConfiguration {

    @Bean
    SecretCipher secretCipher(MailerSettings settings) {
        Secrets secrets = settings.secrets();
        return new AesGcmSecretCipher("mailer.secrets", secrets.masterKey(), secrets.masterKeyId());
    }
}
