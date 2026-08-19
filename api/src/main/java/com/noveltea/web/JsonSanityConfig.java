package com.noveltea.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rejects NUL bytes anywhere in a JSON request body.
 *
 * <p>Applied at deserialization so it covers every field of every endpoint, including ones
 * added later, rather than depending on each service to remember. A NUL in a title is never
 * meaningful text; it is rejected rather than stripped, because silently altering what an
 * author submitted is worse than refusing it.
 */
@Configuration
public class JsonSanityConfig {

    @Bean
    public SimpleModule rejectNulBytesModule() {
        SimpleModule module = new SimpleModule("reject-nul-bytes");
        module.addDeserializer(String.class, new StringDeserializer() {
            @Override
            public String deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                String value = super.deserialize(parser, context);
                if (value != null && value.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("a null character is not valid text");
                }
                return value;
            }
        });
        return module;
    }
}
