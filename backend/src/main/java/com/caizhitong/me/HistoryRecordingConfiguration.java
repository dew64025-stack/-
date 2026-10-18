package com.caizhitong.me;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class HistoryRecordingConfiguration implements WebMvcConfigurer {
    private final HistoryRecorder recorder;

    public HistoryRecordingConfiguration(HistoryRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(recorder).addPathPatterns("/api/v1/**");
    }
}
