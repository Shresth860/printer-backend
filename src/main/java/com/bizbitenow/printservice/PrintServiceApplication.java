package com.bizbitenow.printservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class  PrintServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrintServiceApplication.class, args);
    }
}
