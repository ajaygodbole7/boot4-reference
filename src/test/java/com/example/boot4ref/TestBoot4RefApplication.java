package com.example.boot4ref;

import org.springframework.boot.SpringApplication;

public class TestBoot4RefApplication {

    public static void main(String[] args) {
        SpringApplication.from(Boot4RefApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
