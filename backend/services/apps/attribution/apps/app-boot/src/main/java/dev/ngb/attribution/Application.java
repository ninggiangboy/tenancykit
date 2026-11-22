package dev.ngb.attribution;

import dev.ngb.attribution.boot.config.AttributionSettings;
import dev.ngb.attribution.boot.config.SuiteProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SuiteProperties.class, AttributionSettings.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
