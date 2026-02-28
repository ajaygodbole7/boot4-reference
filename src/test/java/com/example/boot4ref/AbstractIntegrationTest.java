package com.example.boot4ref;

import com.example.boot4ref.config.DataSourceProxyConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, DataSourceProxyConfiguration.class})
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
}
