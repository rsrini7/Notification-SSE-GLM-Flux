package com.example.broadcast.shared.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@PropertySource("classpath:sql/queries.properties")
public class SqlQueryProvider {

    private final Environment env;

    public String getQuery(String key) {
        String query = env.getProperty(key);
        if (query == null) {
            throw new IllegalArgumentException("SQL query not found for key: " + key);
        }
        return query;
    }
}