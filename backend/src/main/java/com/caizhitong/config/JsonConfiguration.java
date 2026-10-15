package com.caizhitong.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonConfiguration {
    @Bean public JsonMapper jsonMapper() {
        return JsonMapper.builder().findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }
}
