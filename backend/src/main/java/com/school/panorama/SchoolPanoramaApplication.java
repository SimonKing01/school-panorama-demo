package com.school.panorama;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.school.panorama.mapper")
public class SchoolPanoramaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchoolPanoramaApplication.class, args);
    }
}
