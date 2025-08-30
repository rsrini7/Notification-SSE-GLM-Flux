package com.example.broadcast.shared.config;

import com.example.broadcast.shared.converter.TimestampToOffsetDateTimeConverter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.util.List;

@Configuration
public class JdbcConfig {

    @Bean
    public JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(
            new TimestampToOffsetDateTimeConverter()            
        ));
    }
}