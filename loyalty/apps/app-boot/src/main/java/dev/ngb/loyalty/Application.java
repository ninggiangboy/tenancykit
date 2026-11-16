package dev.ngb.loyalty;

import dev.ngb.loyalty.boot.config.LoyaltySettings;
import dev.ngb.loyalty.boot.config.SuiteProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SuiteProperties.class, LoyaltySettings.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
