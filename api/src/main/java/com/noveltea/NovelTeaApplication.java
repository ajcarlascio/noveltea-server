package com.noveltea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
// Scanned rather than listed: an @EnableConfigurationProperties list is edited by hand
// and silently omits a new record, which fails only at runtime as a missing bean.
@ConfigurationPropertiesScan
public class NovelTeaApplication {
    public static void main(String[] args) {
        SpringApplication.run(NovelTeaApplication.class, args);
    }
}
