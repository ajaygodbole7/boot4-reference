package com.example.boot4ref.common.rest;

import com.example.boot4ref.config.ApplicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that dev profile adds exception class and stack trace to RFC 9457 responses.
 * Separate test class because it requires a different Spring profile.
 */
@WebMvcTest(controllers = ExceptionTranslatorTest.TestController.class)
@Import({ExceptionTranslator.class, ExceptionTranslatorTest.TestController.class})
@EnableConfigurationProperties(ApplicationProperties.class)
@ActiveProfiles("dev")
class ExceptionTranslatorDevProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldIncludeStackTraceInDevProfile() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.exception").value("java.lang.RuntimeException"))
                .andExpect(jsonPath("$.stackTrace").exists())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/500"))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected internal error occurred"));
    }
}
