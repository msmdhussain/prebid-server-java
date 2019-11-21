package org.prebid.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.Json;
import org.junit.BeforeClass;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.json.ObjectMapperConfigurer;

public abstract class VertxTest {

    protected static JacksonMapper jacksonMapper;
    protected static ObjectMapper mapper;

    @BeforeClass
    public static void beforeClass() {
        ObjectMapperConfigurer.configure();
        mapper = Json.mapper;
        jacksonMapper = new JacksonMapper(mapper);
    }
}
