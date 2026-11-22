package dev.ngb.billing;

import dev.ngb.billing.boot.config.JobsProperties;
import dev.ngb.billing.boot.config.PortalProperties;
import dev.ngb.billing.boot.config.SuiteProperties;
import dev.ngb.billing.boot.config.WebhookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({PortalProperties.class, JobsProperties.class, WebhookProperties.class, SuiteProperties.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
