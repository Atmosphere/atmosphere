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

    // === Path 1: Existing Spring bean lookup ===

    @Test
    void springBeanFoundAndReturned() throws Exception {
        TestService result = factory.newClassInstance(TestService.class, TestService.class);
        assertThat(result).isNotNull();
        assertThat(result).isSameAs(applicationContext.getBean(TestService.class));
    }

    @Test
    void springBeanReturnsSameInstanceEveryTime() throws Exception {
        TestService first = factory.newClassInstance(TestService.class, TestService.class);
        TestService second = factory.newClassInstance(TestService.class, TestService.class);
        assertThat(first).isSameAs(second);
    }

    // === Path 2: Spring createBean (full autowiring) ===

    @Test
    void nonBeanClassCreatedAndAutowired() throws Exception {
        AutowiredComponent result = factory.newClassInstance(
                AutowiredComponent.class, AutowiredComponent.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).isNotNull();
    }

    @Test
    void nonBeanWithMultipleSpringDeps() throws Exception {
        MultiDepsComponent result = factory.newClassInstance(
                MultiDepsComponent.class, MultiDepsComponent.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("TestService via @Autowired").isNotNull();
        assertThat(result.otherService).as("OtherService via @Autowired").isNotNull();
    }

    // === Path 3: Hybrid injection (Atmosphere + Spring) ===

    @Test
    void hybridInjectionInjectsSpringBeansAfterAtmosphereFallback() throws Exception {
        HybridComponent result = factory.newClassInstance(
                HybridComponent.class, HybridComponent.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("Spring bean should be injected").isNotNull();
        assertThat(result.testService).isSameAs(applicationContext.getBean(TestService.class));
    }

    @Test
    void hybridInjectionWithMultipleSpringBeans() throws Exception {
        HybridMultiComponent result = factory.newClassInstance(
                HybridMultiComponent.class, HybridMultiComponent.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("TestService should be injected").isNotNull();
        assertThat(result.otherService).as("OtherService should be injected").isNotNull();
        assertThat(result.testService).isSameAs(applicationContext.getBean(TestService.class));
        assertThat(result.otherService).isSameAs(applicationContext.getBean(OtherService.class));
    }

    @Test
    void hybridInjectionWithAtmosphereResourceAndEvent() throws Exception {
        // Simulates a real @ManagedService with AtmosphereResource + AtmosphereResourceEvent
        // (both Atmosphere-managed) plus a Spring bean
        FullManagedServiceLike result = factory.newClassInstance(
                FullManagedServiceLike.class, FullManagedServiceLike.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("Spring bean should be injected").isNotNull();
        // AtmosphereResource and AtmosphereResourceEvent are injected per-request by
        // Atmosphere, so they'll be null at creation time — that's expected
    }

    @Test
    void hybridInjectionLeavesNonSpringFieldsNull() throws Exception {
        // Fields whose type is NOT a Spring bean should remain null
        HybridWithUnresolvable result = factory.newClassInstance(
                HybridWithUnresolvable.class, HybridWithUnresolvable.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("Spring bean should be injected").isNotNull();
        assertThat(result.unresolvable).as("Non-Spring field stays null").isNull();
    }

    @Test
    void hybridInjectionInheritsFromParent() throws Exception {
        // Spring bean declared in parent class should also get injected
        ChildWithInheritedInject result = factory.newClassInstance(
                ChildWithInheritedInject.class, ChildWithInheritedInject.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("Parent @Inject field should be injected").isNotNull();
        assertThat(result.otherService).as("Child @Inject field should be injected").isNotNull();
    }

    @Test
    void hybridInjectionWithAutowiredSpringBeans() throws Exception {
        HybridAutowiredComponent result = factory.newClassInstance(
                HybridAutowiredComponent.class, HybridAutowiredComponent.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("@Autowired Spring bean should be injected").isNotNull();
        assertThat(result.testService).isSameAs(applicationContext.getBean(TestService.class));
    }

    @Test
    void hybridInjectionMixedInjectAndAutowired() throws Exception {
        HybridMixedAnnotations result = factory.newClassInstance(
                HybridMixedAnnotations.class, HybridMixedAnnotations.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("@Inject Spring bean").isNotNull();
        assertThat(result.otherService).as("@Autowired Spring bean").isNotNull();
        assertThat(result.testService).isSameAs(applicationContext.getBean(TestService.class));
        assertThat(result.otherService).isSameAs(applicationContext.getBean(OtherService.class));
    }

    @Test
    void hybridInjectionAutowiredWithMultipleBeans() throws Exception {
        HybridMultiAutowired result = factory.newClassInstance(
                HybridMultiAutowired.class, HybridMultiAutowired.class);
        assertThat(result).isNotNull();
        assertThat(result.testService).as("@Autowired TestService").isNotNull();
        assertThat(result.otherService).as("@Autowired OtherService").isNotNull();
    }

    // === Edge cases ===

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
    void classWithNoInjectFieldsStillWorks() throws Exception {
        NoInjectComponent result = factory.newClassInstance(
                NoInjectComponent.class, NoInjectComponent.class);
        assertThat(result).isNotNull();
    }

    @Test
    void classWithOnlyAtmosphereDepsStillWorks() throws Exception {
        AtmosphereOnlyComponent result = factory.newClassInstance(
                AtmosphereOnlyComponent.class, AtmosphereOnlyComponent.class);
        assertThat(result).isNotNull();
    }

    // === Configuration and fixtures ===

    @Configuration
    static class TestConfig {
        @Bean
        public TestService testService() {
            return new TestService();
        }

        @Bean
        public OtherService otherService() {
            return new OtherService();
        }
    }

    public static class TestService {
    }

    public static class OtherService {
    }

    public static class UnresolvableService {
    }

    // Path 2 fixtures

    public static class AutowiredComponent {
        @Autowired
        TestService testService;
    }

    public static class MultiDepsComponent {
        @Autowired
        TestService testService;

        @Autowired
        OtherService otherService;
    }

    // Path 3 fixtures (hybrid: @Inject with Atmosphere + Spring types)

    public static class HybridComponent {
        @Inject
        AtmosphereResource resource;

        @Inject
        TestService testService;
    }

    public static class HybridMultiComponent {
        @Inject
        AtmosphereResource resource;

        @Inject
        TestService testService;

        @Inject
        OtherService otherService;
    }

    public static class FullManagedServiceLike {
        @Inject
        AtmosphereResource resource;

        @Inject
        AtmosphereResourceEvent event;

        @Inject
        TestService testService;
    }

    public static class HybridWithUnresolvable {
        @Inject
        AtmosphereResource resource;

        @Inject
        TestService testService;

        @Inject
        UnresolvableService unresolvable;
    }

    public static class ParentWithInject {
        @Inject
        TestService testService;
    }

    public static class ChildWithInheritedInject extends ParentWithInject {
        @Inject
        AtmosphereResource resource;

        @Inject
        OtherService otherService;
    }

    // Path 3 fixtures (hybrid: @Autowired with Atmosphere + Spring types)

    public static class HybridAutowiredComponent {
        @Inject
        AtmosphereResource resource;

        @Autowired
        TestService testService;
    }

    public static class HybridMixedAnnotations {
        @Inject
        AtmosphereResource resource;

        @Inject
        TestService testService;

        @Autowired
        OtherService otherService;
    }

    public static class HybridMultiAutowired {
        @Inject
        AtmosphereResource resource;

        @Autowired
        TestService testService;

        @Autowired
        OtherService otherService;
    }

    // Edge case fixtures

    public static class NoInjectComponent {
        String name = "no-inject";
    }

    public static class AtmosphereOnlyComponent {
        @Inject
        AtmosphereResource resource;

        @Inject
        AtmosphereResourceEvent event;
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
}
