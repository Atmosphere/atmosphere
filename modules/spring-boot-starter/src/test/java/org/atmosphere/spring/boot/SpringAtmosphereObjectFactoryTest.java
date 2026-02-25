/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.spring.boot;

import java.io.IOException;

import jakarta.inject.Inject;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.inject.InjectableObjectFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAtmosphereObjectFactoryTest {

    private SpringAtmosphereObjectFactory factory;
    private AnnotationConfigApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        applicationContext = new AnnotationConfigApplicationContext(TestConfig.class);
        factory = new SpringAtmosphereObjectFactory(applicationContext);

        AtmosphereFramework framework = new AtmosphereFramework();
        factory.configure(framework.getAtmosphereConfig());
    }

    @Test
    void springBeanFoundAndReturned() throws Exception {
        TestService result = factory.newClassInstance(TestService.class, TestService.class);
        assertThat(result).isNotNull();
        assertThat(result).isSameAs(applicationContext.getBean(TestService.class));
    }

    @Test
    void nonBeanClassCreatedAndAutowired() throws Exception {
        AutowiredComponent result = factory.newClassInstance(
                AutowiredComponent.class, AutowiredComponent.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).isNotNull();
    }

    @Test
    void isAssignableFromInjectableObjectFactory() {
        assertThat(InjectableObjectFactory.class.isAssignableFrom(factory.getClass())).isTrue();
    }

    @Test
    void fallbackForAtmosphereInternalClasses() throws Exception {
        AtmosphereHandler result = factory.newClassInstance(
                AtmosphereHandler.class, SimpleHandler.class);
        assertThat(result).isNotNull();
    }

    @Test
    void hybridInjectionInjectsSpringBeansAfterAtmosphereFallback() throws Exception {
        // HybridComponent has @Inject for both AtmosphereResource (Atmosphere-managed)
        // and TestService (Spring-managed). Spring's createBean() will fail because
        // it can't resolve AtmosphereResource, so the factory should fall back to
        // Atmosphere's injector and then inject Spring beans into remaining fields.
        HybridComponent result = factory.newClassInstance(
                HybridComponent.class, HybridComponent.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("Spring bean should be injected").isNotNull();
        assertThat(result.testService).isSameAs(applicationContext.getBean(TestService.class));
    }

    @Configuration
    static class TestConfig {
        @Bean
        public TestService testService() {
            return new TestService();
        }
    }

    static class TestService {
    }

    static class AutowiredComponent {
        @Autowired
        TestService testService;
    }

    public static class SimpleHandler implements AtmosphereHandler {
        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        }

        @Override
        public void destroy() {
        }
    }

    /**
     * Simulates a {@code @ManagedService} that uses {@code @Inject} for both
     * Atmosphere-managed objects and Spring beans. Spring's {@code createBean()}
     * will fail because AtmosphereResource is not a Spring bean, triggering the
     * hybrid injection path.
     */
    public static class HybridComponent {
        @Inject
        AtmosphereResource resource;

        @Inject
        TestService testService;
    }
}
