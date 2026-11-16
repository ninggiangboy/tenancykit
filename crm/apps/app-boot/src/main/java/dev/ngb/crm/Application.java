package dev.ngb.crm;

import dev.ngb.crm.boot.config.CrmSettings;
import dev.ngb.crm.boot.config.SuiteProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SuiteProperties.class, CrmSettings.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
