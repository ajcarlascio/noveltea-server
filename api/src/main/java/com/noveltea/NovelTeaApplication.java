package com.noveltea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Scanned rather than listed: an @EnableConfigurationProperties list is maintained by hand
// and silently omits a new record, which then fails at runtime as a missing bean.
@ConfigurationPropertiesScan
@EnableScheduling
public class NovelTeaApplication {
    public static void main(String[] args) {
        SpringApplication.run(NovelTeaApplication.class, args);
    }
}
