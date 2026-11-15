package dev.ngb.mailroom;

import dev.ngb.mailroom.boot.config.MailroomSettings;
import dev.ngb.mailroom.boot.config.SuiteProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SuiteProperties.class, MailroomSettings.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
