package com.novel.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 单机长耗时维护任务专用线程池。
 *
 * <p>个人写作场景不需要消息队列。单线程加零容量队列可以保证导入和
 * Milvus finalize 不会在 JVM 内静默排队，任务冲突时直接返回可重试结果。</p>
 */
@Configuration
public class LocalTaskExecutorConfig {

    @Bean(name = "localTaskExecutor")
    public ThreadPoolTaskExecutor localTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("novel-local-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
