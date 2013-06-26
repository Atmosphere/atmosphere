/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.cpr;

import org.atmosphere.config.service.AsyncSupportListenerService;
import org.atmosphere.config.service.AsyncSupportService;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.config.service.AtmosphereService;
import org.atmosphere.config.service.BroadcasterCacheInspectorService;
import org.atmosphere.config.service.BroadcasterCacheService;
import org.atmosphere.config.service.BroadcasterFactoryService;
import org.atmosphere.config.service.BroadcasterFilterService;
import org.atmosphere.config.service.BroadcasterListenerService;
import org.atmosphere.config.service.BroadcasterService;
import org.atmosphere.config.service.EndpoinMapperService;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.MeteorService;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.config.service.WebSocketProcessorService;
import org.atmosphere.config.service.WebSocketProtocolService;
import org.atmosphere.util.annotation.AnnotationDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Set;

/**
 * An {@link AnnotationProcessor} that selects between a ServletContextInitializer based scanner, and
 * a bytecode based scanner based on <a href="https://github.com/rmuller/infomas-asl"></a>
 * <p/>
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultAnnotationProcessor implements AnnotationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAnnotationProcessor.class);


    /**
     * The attribute name under which the annotations are stored in the servlet context
     */
    public static final String ANNOTATION_ATTRIBUTE = "org.atmosphere.cpr.ANNOTATION_MAP";

    private AnnotationProcessor delegate;

    @Override
    public AnnotationProcessor configure(final AtmosphereFramework framework) {
        ServletContext sc = framework.getServletContext();

        Map<Class<? extends Annotation>, Set<Class<?>>> annotations = (Map<Class<? extends Annotation>, Set<Class<?>>>) sc.getAttribute(ANNOTATION_ATTRIBUTE);

        if (annotations == null || annotations.isEmpty()) {
            delegate = new BytecodeBasedAnnotationProcessor();
        } else {
            delegate = new ServletContainerInitializerAnnotationProcessor(annotations, framework);
        }
        delegate.configure(framework);
        return this;
    }

    @Override
    public AnnotationProcessor scan(final File rootDir) throws IOException {
        delegate.scan(rootDir);
        return this;
    }

    @Override
    public AnnotationProcessor scan(final String packageName) throws IOException {
        delegate.scan(packageName);
        return this;
    }

    @Override
    public void destroy() {
        if(delegate != null) {
            delegate.destroy();
        }
    }

    private static final class ServletContainerInitializerAnnotationProcessor implements AnnotationProcessor {

        private final Map<Class<? extends Annotation>, Set<Class<?>>> annotations;
        private final AtmosphereFramework framework;

        /**
         * This is not great, but we can't differentiate based on the file, so we just do one scan
         * of everything in the war. It would be nice to change to the API to make this a bit cleaner
         * but it looks like it is a public API.
         */
        private boolean alreadyScanned = false;

        private ServletContainerInitializerAnnotationProcessor(final Map<Class<? extends Annotation>, Set<Class<?>>> annotations, final AtmosphereFramework framework) {
            this.annotations = annotations;
            this.framework = framework;
        }

        @Override
        public AnnotationProcessor configure(final AtmosphereFramework framework) {
            return this;
        }

        @Override
        public AnnotationProcessor scan(final File rootDir) throws IOException {
            if (alreadyScanned) {
                return this;
            }
            alreadyScanned = true;
            for (Map.Entry<Class<? extends Annotation>, Set<Class<?>>> entry : annotations.entrySet()) {
                for (Class<?> clazz : entry.getValue()) {
                    AnnotationHandler.handleAnnotation(framework, entry.getKey(), clazz);
                }
            }
            return this;
        }

        @Override
        public AnnotationProcessor scan(final String packageName) throws IOException {
            for (Map.Entry<Class<? extends Annotation>, Set<Class<?>>> entry : annotations.entrySet()) {
                for (Class<?> clazz : entry.getValue()) {
                    if (clazz.getPackage().getName().startsWith(packageName)) {
                        AnnotationHandler.handleAnnotation(framework, entry.getKey(), clazz);
                    }
                }
            }
            return this;
        }

        @Override
        public void destroy() {

        }
    }

    private static final class BytecodeBasedAnnotationProcessor implements AnnotationProcessor {

        protected AnnotationDetector detector;

        public BytecodeBasedAnnotationProcessor() {
        }

        @Override
        public AnnotationProcessor configure(final AtmosphereFramework framework) {

            final AnnotationDetector.TypeReporter reporter = new AnnotationDetector.TypeReporter() {
                @SuppressWarnings("unchecked")
                @Override
                public Class<? extends Annotation>[] annotations() {
                    return new Class[]{
                            AtmosphereHandlerService.class,
                            BroadcasterCacheService.class,
                            BroadcasterFilterService.class,
                            BroadcasterFactoryService.class,
                            BroadcasterService.class,
                            MeteorService.class,
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
                            EndpoinMapperService.class,
                    };
                }

                @Override
                public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
                    logger.info("Found Annotation in {} being scanned: {}", className, annotation);
                    try {
                        final Class<?> discoveredClass = loadClass(className);
                        AnnotationHandler.handleAnnotation(framework, annotation, discoveredClass);
                    } catch (Exception e) {
                        logger.warn("Could not load discovered class", e);
                    }
                }

            };
            detector = new AnnotationDetector(reporter);
            return this;
        }

        @Override
        public AnnotationProcessor scan(File rootDir) throws IOException {
            detector.detect(rootDir);
            return this;
        }

        @Override
        public AnnotationProcessor scan(String packageName) throws IOException {
            logger.trace("Scanning @Service annotations in {}", packageName);
            detector.detect(packageName);
            return this;
        }

        @Override
        public void destroy() {
            detector.destroy();
        }


        protected Class<?> loadClass(String className) throws Exception {
            try {
                return Thread.currentThread().getContextClassLoader().loadClass(className);
            } catch (Throwable t) {
                return getClass().getClassLoader().loadClass(className);
            }
        }
    }
}
