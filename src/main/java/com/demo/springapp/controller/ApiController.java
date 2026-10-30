package com.demo.springapp.controller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.version}")
    private String appVersion;

    @Value("${app.environment}")
    private String environment;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("application", appName);
        body.put("version", appVersion);
        body.put("hostname", hostname());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", appName);
        body.put("version", appVersion);
        body.put("environment", environment);
        body.put("hostname", hostname());
        body.put("javaVersion", System.getProperty("java.version"));
        return body;
    }

    @GetMapping("/greeting")
    public Map<String, String> greeting(@RequestParam(defaultValue = "World") String name) {
        return Map.of("message", "Hello, " + name + "!");
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
