package browserAI.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("agent-scheduler-");
        scheduler.setErrorHandler(t ->
            org.slf4j.LoggerFactory.getLogger("Scheduler").error("Scheduled task error: {}", t.getMessage()));
        scheduler.initialize();
        return scheduler;
    }
}
