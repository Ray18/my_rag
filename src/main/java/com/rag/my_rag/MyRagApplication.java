package com.rag.my_rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MyRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyRagApplication.class, args);
    }
}
