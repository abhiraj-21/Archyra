package com.abhiraj.archyra.common;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Background execution for analyses. A dedicated, bounded pool keeps repository analysis (CPU and
 * IO heavy) from competing with request threads. Redis/RabbitMQ are deliberately not used yet.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	public static final String ANALYSIS_EXECUTOR = "analysisExecutor";
	public static final String AI_INDEX_EXECUTOR = "aiIndexExecutor";

	@Bean(ANALYSIS_EXECUTOR)
	public Executor analysisExecutor(
			@Value("${archyra.analysis.executor.pool-size:2}") int poolSize,
			@Value("${archyra.analysis.executor.queue-capacity:20}") int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix("analysis-");
		// Reject (instead of running on the request thread) when the queue is full.
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}

	/**
	 * Embeds the code chunks of finished analyses (Ask AI). One worker and a small queue: embedding is CPU heavy and
	 * must never compete with the analyses. A full queue is not an error: the index stays INDEXING and the sweeper
	 * picks it up again.
	 */
	@Bean(AI_INDEX_EXECUTOR)
	public Executor aiIndexExecutor(@Value("${archyra.ai.index.queue-capacity:20}") int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix("ai-index-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(false); // an interrupted job resumes from its INDEXING row at startup
		return executor;
	}
}
