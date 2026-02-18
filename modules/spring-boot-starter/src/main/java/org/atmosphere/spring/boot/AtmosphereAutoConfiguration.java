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

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.service.AsyncSupportListenerService;
import org.atmosphere.config.service.AsyncSupportService;
import org.atmosphere.config.service.AtmosphereFrameworkListenerService;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.config.service.AtmosphereResourceFactoryService;
import org.atmosphere.config.service.AtmosphereResourceListenerService;
import org.atmosphere.config.service.AtmosphereService;
import org.atmosphere.config.service.BroadcasterCacheInspectorService;
import org.atmosphere.config.service.BroadcasterCacheListenerService;
import org.atmosphere.config.service.BroadcasterCacheService;
import org.atmosphere.config.service.BroadcasterFactoryService;
import org.atmosphere.config.service.BroadcasterFilterService;
import org.atmosphere.config.service.BroadcasterListenerService;
import org.atmosphere.config.service.BroadcasterService;
import org.atmosphere.config.service.EndpointMapperService;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.RoomService;
import org.atmosphere.config.service.UUIDProviderService;
import org.atmosphere.config.service.WebSocketFactoryService;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.config.service.WebSocketProcessorService;
import org.atmosphere.config.service.WebSocketProtocolService;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.DefaultAnnotationProcessor;
import org.atmosphere.room.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.type.filter.AnnotationTypeFilter;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(AtmosphereServlet.class)
@EnableConfigurationProperties(AtmosphereProperties.class)
@ImportRuntimeHints(AtmosphereRuntimeHints.class)
public class AtmosphereAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAutoConfiguration.class);

    @SuppressWarnings("unchecked")
    private static final Class<? extends Annotation>[] ATMOSPHERE_ANNOTATIONS = new Class[]{
            AtmosphereHandlerService.class,
            BroadcasterCacheService.class,
            BroadcasterFilterService.class,
            BroadcasterFactoryService.class,
            BroadcasterService.class,
            WebSocketFactoryService.class,
            WebSocketHandlerService.class,
            WebSocketProtocolService.class,
            AtmosphereInterceptorService.class,
            BroadcasterListenerService.class,
            AsyncSupportService.class,
            AsyncSupportListenerService.class,
            WebSocketProcessorService.class,
            BroadcasterCacheInspectorService.class,
            ManagedService.class,
            AtmosphereService.class,
            EndpointMapperService.class,
            BroadcasterCacheListenerService.class,
            AtmosphereAnnotation.class,
            AtmosphereResourceFactoryService.class,
            AtmosphereFrameworkListenerService.class,
            AtmosphereResourceListenerService.class,
            UUIDProviderService.class,
            RoomService.class
    };

    @Bean
    @ConditionalOnMissingBean
    public SpringAtmosphereObjectFactory springAtmosphereObjectFactory(ApplicationContext applicationContext) {
        return new SpringAtmosphereObjectFactory(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereServlet atmosphereServlet(SpringAtmosphereObjectFactory objectFactory,
                                               AtmosphereProperties properties) {
        // Pre-scan for Atmosphere annotations using Spring's classpath scanner.
        // Spring Boot's embedded containers do not process @HandlesTypes from
        // ServletContainerInitializer, so Atmosphere's built-in annotation scanning
        // receives no classes. We bridge this gap by scanning here and injecting
        // the results into the servlet context right before framework.init() reads them.
        Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap = scanAnnotations(properties);

        var servlet = new AnnotationAwareAtmosphereServlet(annotationMap);
        servlet.framework().objectFactory(objectFactory);
        return servlet;
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereFramework atmosphereFramework(AtmosphereServlet servlet) {
        return servlet.framework();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoomManager roomManager(AtmosphereFramework framework) {
        return RoomManager.getOrCreate(framework);
    }

    private Map<Class<? extends Annotation>, Set<Class<?>>> scanAnnotations(
            AtmosphereProperties properties) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        for (Class<? extends Annotation> annotation : ATMOSPHERE_ANNOTATIONS) {
            scanner.addIncludeFilter(new AnnotationTypeFilter(annotation));
        }

        Set<Class<?>> classes = new HashSet<>();

        // Scan Atmosphere's annotation processor package
        scanPackage(scanner, "org.atmosphere.annotation", classes);

        // Scan user-configured packages
        if (properties.getPackages() != null) {
            for (String pkg : properties.getPackages().split(",")) {
                scanPackage(scanner, pkg.trim(), classes);
            }
        }

        if (!classes.isEmpty()) {
            logger.info("Atmosphere Spring Boot scanner found {} annotated classes", classes.size());
        }

        // Build the same map structure as AnnotationScanningServletContainerInitializer
        Map<Class<? extends Annotation>, Set<Class<?>>> classesByAnnotation = new HashMap<>();
        for (Class<?> clazz : classes) {
            for (Annotation annotation : clazz.getAnnotations()) {
                classesByAnnotation.computeIfAbsent(annotation.annotationType(),
                        k -> new HashSet<>()).add(clazz);
            }
        }
        return classesByAnnotation;
    }

    private void scanPackage(ClassPathScanningCandidateComponentProvider scanner,
                             String packageName, Set<Class<?>> classes) {
        for (BeanDefinition bd : scanner.findCandidateComponents(packageName)) {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                logger.warn("Could not load Atmosphere annotated class: {}", bd.getBeanClassName());
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "atmosphereServletRegistration")
    public ServletRegistrationBean<AtmosphereServlet> atmosphereServletRegistration(
            AtmosphereServlet servlet, AtmosphereProperties properties) {

        ServletRegistrationBean<AtmosphereServlet> registration =
                new ServletRegistrationBean<>(servlet, properties.getServletPath());
        registration.setName("atmosphereServlet");
        registration.setLoadOnStartup(properties.getOrder());
        registration.setAsyncSupported(true);

        Map<String, String> initParams = registration.getInitParameters();

        initParams.put(ApplicationConfig.DISABLE_ATMOSPHERE_INITIALIZER, "true");

        if (properties.getPackages() != null) {
            initParams.put(ApplicationConfig.ANNOTATION_PACKAGE, properties.getPackages());
        }

        initParams.put(ApplicationConfig.PROPERTY_SESSION_SUPPORT,
                String.valueOf(properties.isSessionSupport()));

        if (properties.getBroadcasterClass() != null) {
            initParams.put(ApplicationConfig.BROADCASTER_CLASS, properties.getBroadcasterClass());
        }

        if (properties.getBroadcasterCacheClass() != null) {
            initParams.put(ApplicationConfig.BROADCASTER_CACHE, properties.getBroadcasterCacheClass());
        }

        if (properties.getWebsocketSupport() != null) {
            initParams.put(ApplicationConfig.WEBSOCKET_SUPPORT,
                    String.valueOf(properties.getWebsocketSupport()));
        }

        if (properties.getHeartbeatIntervalInSeconds() != null) {
            initParams.put(ApplicationConfig.HEARTBEAT_INTERVAL_IN_SECONDS,
                    String.valueOf(properties.getHeartbeatIntervalInSeconds()));
        }

        initParams.putAll(properties.getInitParams());

        return registration;
    }

    /**
     * Named subclass of {@link AtmosphereServlet} that injects the pre-scanned
     * annotation map into the servlet context before framework initialization.
     * Using a named class (instead of an anonymous one) ensures stable class
     * naming for Spring AOT / GraalVM native image support.
     */
    static class AnnotationAwareAtmosphereServlet extends AtmosphereServlet {

        private final Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap;

        AnnotationAwareAtmosphereServlet(
                Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap) {
            this.annotationMap = annotationMap;
        }

        @Override
        public void init(ServletConfig sc) throws ServletException {
            // Set the annotation map just before framework.init() reads it.
            // This must happen here (not in a ServletContextInitializer) because
            // Atmosphere's AnnotationScanningServletContainerInitializer runs
            // after Spring's initializers and overwrites the attribute with an
            // empty map.
            sc.getServletContext().setAttribute(
                    DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE, annotationMap);
            super.init(sc);
        }
    }
}
