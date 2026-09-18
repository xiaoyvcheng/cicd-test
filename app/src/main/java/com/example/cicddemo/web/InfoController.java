package com.example.cicddemo.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.cicddemo.config.AppProperties;

@RestController
@RequestMapping("/api")
public class InfoController {

    private final AppProperties appProperties;
    private final BuildProperties buildProperties;
    private final GitProperties gitProperties;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    public InfoController(
            AppProperties appProperties,
            BuildProperties buildProperties,
            @Autowired(required = false) GitProperties gitProperties) {
        this.appProperties = appProperties;
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appName", appProperties.name());
        body.put("env", appProperties.env());
        body.put("profile", activeProfile);
        body.put("version", buildProperties.getVersion());
        body.put("buildTime", buildProperties.getTime());
        body.put("gitCommit", gitProperties != null ? gitProperties.getShortCommitId() : "unknown");
        body.put("gitBranch", gitProperties != null ? gitProperties.getBranch() : "unknown");
        return body;
    }
}
