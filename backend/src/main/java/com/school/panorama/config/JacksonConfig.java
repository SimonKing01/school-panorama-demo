package com.school.panorama.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/** 统一契约里的三种时间格式：时间戳、时间、日期。 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME = "yyyy-MM-dd'T'HH:mm:ss";
    private static final String TIME = "HH:mm";
    private static final String DATE = "yyyy-MM-dd";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.timeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            builder.simpleDateFormat(DATE_TIME);
            builder.serializers(new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME)));
            builder.deserializers(new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME)));
            builder.serializers(new LocalTimeSerializer(DateTimeFormatter.ofPattern(TIME)));
            builder.deserializers(new LocalTimeDeserializer(DateTimeFormatter.ofPattern(TIME)));
            builder.serializers(new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE)));
            builder.deserializers(new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATE)));
        };
    }
}
