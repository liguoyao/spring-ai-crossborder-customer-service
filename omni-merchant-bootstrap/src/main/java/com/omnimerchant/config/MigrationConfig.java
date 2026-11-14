package com.omnimerchant.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;

@Configuration
public class MigrationConfig {

    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public Flyway businessFlyway(
            @Qualifier("dataSource") DataSource dataSource,
            @Value("${spring.flyway.locations:classpath:db/migration/mysql}") String configuredLocations,
            @Value("${spring.flyway.url:}") String migrationUrl,
            @Value("${spring.flyway.user:}") String migrationUser,
            @Value("${spring.flyway.password:}") String migrationPassword,
            @Value("${spring.datasource.druid.url:}") String jdbcUrl,
            @Value("${spring.datasource.druid.username:}") String jdbcUser,
            @Value("${spring.datasource.druid.password:}") String jdbcPassword) {
        var locations = Arrays.stream(configuredLocations.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
        var configuration = Flyway.configure();
        if (migrationUrl != null && !migrationUrl.isBlank()) {
            // Explicit migration connection (e.g. prod) - already a plain DataSource.
            configuration.dataSource(migrationUrl, migrationUser, migrationPassword);
        } else if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            // Use a plain (non-Druid, non-WallFilter) DataSource for migrations so the
            // SQL firewall does not reject comments / DDL in migration scripts.
            configuration.dataSource(jdbcUrl, jdbcUser, jdbcPassword);
        } else {
            configuration.dataSource(dataSource);
        }
        return configuration
                .locations(locations)
                .table("flyway_schema_history")
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .load();
    }
}
