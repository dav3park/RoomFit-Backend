package com.roomfit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RoomfitApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoomfitApplication.class, args);
    }
}

