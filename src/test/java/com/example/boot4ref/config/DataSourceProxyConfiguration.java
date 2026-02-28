package com.example.boot4ref.config;

import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Wraps the real DataSource with datasource-proxy for query count assertions.
 *
 * <p>Uses a BeanPostProcessor to avoid circular dependency issues with
 * {@code @Primary} + {@code @Qualifier} patterns in Boot 4.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DataSourceProxyConfiguration {

    @Bean
    public static org.springframework.beans.factory.config.BeanPostProcessor dataSourceProxy() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof net.ttddyy.dsproxy.support.ProxyDataSource)) {
                    return ProxyDataSourceBuilder
                            .create(ds)
                            .name("test-ds")
                            .countQuery()
                            .build();
                }
                return bean;
            }
        };
    }
}
