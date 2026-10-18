package com.caizhitong.assistant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** D 模块 AI 助手任务轮询；测试中关闭。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.assistant.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AssistantScheduling {
    private final AssistantWorker worker;

    public AssistantScheduling(AssistantWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.assistant.poll-ms:5000}")
    public void run() {
        worker.run();
    }
}
