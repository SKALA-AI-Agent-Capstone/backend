package org.skala.capstone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BeautyApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(BeautyApiApplication.class, args);
    }
}
