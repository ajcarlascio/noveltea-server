package com.noveltea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NovelTeaApplication {
    public static void main(String[] args) {
        SpringApplication.run(NovelTeaApplication.class, args);
    }
}
