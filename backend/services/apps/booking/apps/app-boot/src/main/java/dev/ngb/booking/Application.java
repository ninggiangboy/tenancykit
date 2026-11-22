package dev.ngb.booking;

import dev.ngb.booking.boot.config.BookingSettings;
import dev.ngb.booking.boot.config.SuiteProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SuiteProperties.class, BookingSettings.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
