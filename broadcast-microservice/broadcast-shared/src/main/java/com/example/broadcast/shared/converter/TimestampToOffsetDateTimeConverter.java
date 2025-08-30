package com.example.broadcast.shared.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Converts a legacy java.sql.Timestamp from the JDBC driver
 * into a modern java.time.OffsetDateTime.
 */
@ReadingConverter
public class TimestampToOffsetDateTimeConverter implements Converter<Timestamp, OffsetDateTime> {

    @Override
    public OffsetDateTime convert(Timestamp source) {
        return source == null ? null : source.toInstant().atOffset(ZoneOffset.UTC);
    }
}