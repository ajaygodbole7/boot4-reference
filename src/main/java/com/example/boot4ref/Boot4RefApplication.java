package com.example.boot4ref;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.example.boot4ref.config.ApplicationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ApplicationProperties.class)
@EnableScheduling
public class Boot4RefApplication {
    public static void main(String[] args) {
        SpringApplication.run(Boot4RefApplication.class, args);
    }
}
