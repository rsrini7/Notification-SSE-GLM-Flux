package com.example.broadcast.shared.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Configuration for ShedLock, a distributed scheduler lock.
 * This ensures that scheduled tasks are executed at most once at the same time
 * in a multi-node environment.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S") // Provides a default lock duration as a fallback
@Profile("!checkpoint-build")
public class ShedLockConfig {

    /**
     * Creates the LockProvider bean, which ShedLock uses to manage locks.
     * We are using a JDBC-based provider that stores locks in a database table.
     * @param dataSource The application's configured data source.
     * @return A configured LockProvider.
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime() // It's recommended to use DB time for synchronization
                .build()
        );
    }
}