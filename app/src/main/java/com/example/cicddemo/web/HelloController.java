package com.example.cicddemo.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.cicddemo.config.AppProperties;

@RestController
@RequestMapping("/api")
public class HelloController {

    private final AppProperties appProperties;

    public HelloController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of(
                "message", appProperties.greeting(),
                "env", appProperties.env()
        );
    }
}
