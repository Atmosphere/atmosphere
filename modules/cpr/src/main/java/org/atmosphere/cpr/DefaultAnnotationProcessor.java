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

import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.util.annotation.AnnotationDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.annotation.HandlesTypes;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.List;
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
    private final AnnotationHandler handler;

    private final AnnotationDetector.TypeReporter atmosphereReporter = new AnnotationDetector.TypeReporter() {
        @SuppressWarnings("unchecked")
        @Override
        public Class<? extends Annotation>[] annotations() {
            return new Class[]{
                    AtmosphereAnnotation.class
            };
        }

        @Override
        public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
            try {
                handler.handleProcessor(loadClass(getClass(), className));
            } catch (Exception e) {
                logger.warn("Error scanning @AtmosphereAnnotation", e);
            }
        }
    };

    public DefaultAnnotationProcessor() {
        this.handler = new AnnotationHandler();
    }

    @Override
    public AnnotationProcessor configure(final AtmosphereFramework framework) {
        ServletContext sc = framework.getServletContext();

        Map<Class<? extends Annotation>, Set<Class<?>>> annotations = (Map<Class<? extends Annotation>, Set<Class<?>>>) sc.getAttribute(ANNOTATION_ATTRIBUTE);
        sc.removeAttribute(ANNOTATION_ATTRIBUTE);

        boolean scanForAtmosphereAnnotation = false;
        if (annotations == null || annotations.isEmpty()) {
            delegate = new BytecodeBasedAnnotationProcessor(handler);
            scanForAtmosphereAnnotation = true;
        } else {
            delegate = new ServletContainerInitializerAnnotationProcessor(handler, annotations, framework);
        }
        logger.info("AnnotationProcessor {} being used", delegate.getClass());

        if (scanForAtmosphereAnnotation) {
            scanForAnnotation(framework);
        }

        delegate.configure(framework);
        return this;
    }

    private void scanForAnnotation(AtmosphereFramework f) {
        List<String> packages = f.customAnnotationPackages();
        AnnotationDetector detector = new AnnotationDetector(atmosphereReporter);
        try {
            if (packages.size() > 0) {
                for (String p : packages) {
                    logger.trace("Package {} scanned for @AtmosphereAnnotation", p);
                    detector.detect(p);
                }
            }

            // Now look for application defined annotation
            String path = f.getHandlersPath();
            if (path != null) {
                detector.detect(new File(path));
            }
        } catch (IOException e) {
            logger.warn("Unable to scan annotation", e);
        } finally {
            detector.destroy();
        }
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
    public AnnotationProcessor scanAll() throws IOException {
        delegate.scanAll();
        return this;
    }

    @Override
    public void destroy() {
        if (delegate != null) {
            delegate.destroy();
            handler.destroy();
            // Help GC with the annotations Map.
            delegate = null;
        }
    }

    private static final class ServletContainerInitializerAnnotationProcessor implements AnnotationProcessor {

        private final Map<Class<? extends Annotation>, Set<Class<?>>> annotations;
        private final AtmosphereFramework framework;
        private final AnnotationHandler handler;
        /**
         * This is not great, but we can't differentiate based on the file, so we just do one scan
         * of everything in the war. It would be nice to change to the API to make this a bit cleaner
         * but it looks like it is a public API.
         */
        private boolean alreadyScanned = false;


        private ServletContainerInitializerAnnotationProcessor(AnnotationHandler handler,
                                                               final Map<Class<? extends Annotation>, Set<Class<?>>> annotations,
                                                               final AtmosphereFramework framework) {
            this.annotations = annotations;
            this.framework = framework;
            this.handler = handler;
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

            Set<Class<?>> atmosphereAnnotatedClasses = annotations.get(AtmosphereAnnotation.class);
            boolean handleAtmosphereAnnotation = handleAtmosphereAnnotation(atmosphereAnnotatedClasses);

            for (Map.Entry<Class<? extends Annotation>, Set<Class<?>>> entry : annotations.entrySet()) {
                for (Class<?> clazz : entry.getValue()) {
                    handler.handleAnnotation(framework, entry.getKey(), clazz);
                }
            }

            if (handleAtmosphereAnnotation) {
                scanForCustomAnnotation(atmosphereAnnotatedClasses);
            }
            return this;
        }

        private boolean handleAtmosphereAnnotation(Set<Class<?>> atmosphereAnnotatedClasses) {
            boolean scanForCustomizedAnnotation = false;
            if (atmosphereAnnotatedClasses != null) {
                for (Class<?> clazz : atmosphereAnnotatedClasses) {
                    handler.handleProcessor(clazz);

                    // If larger, a custom annotation has been defined.
                    if (atmosphereAnnotatedClasses.size() > AnnotationScanningServletContainerInitializer.class.getAnnotation(HandlesTypes.class).value().length) {
                        scanForCustomizedAnnotation = true;
                    }
                }
                annotations.remove(AtmosphereAnnotation.class);
            } else {
                logger.error("No @AtmosphereService annotation found. Annotation won't work.");
            }
            return scanForCustomizedAnnotation;
        }

        private void scanForCustomAnnotation(Set<Class<?>> atmosphereAnnotatedClasses) throws IOException {
            BytecodeBasedAnnotationProcessor b = new BytecodeBasedAnnotationProcessor(handler);
            b.configure(framework);
            for (Class<?> clazz : atmosphereAnnotatedClasses) {
                if (clazz.getPackage().getName().startsWith(Processor.class.getName())) {
                    b.scan(clazz.getPackage().getName());
                }
            }
            b.destroy();
        }

        @Override
        public AnnotationProcessor scan(final String packageName) throws IOException {
            Set<Class<?>> atmosphereAnnotatedClasses = annotations.get(AtmosphereAnnotation.class);
            boolean handleAtmosphereAnnotation = handleAtmosphereAnnotation(atmosphereAnnotatedClasses);

            for (Map.Entry<Class<? extends Annotation>, Set<Class<?>>> entry : annotations.entrySet()) {
                for (Class<?> clazz : entry.getValue()) {
                    if (packageName.equals("all") || clazz.getPackage().getName().startsWith(packageName)) {
                        handler.handleAnnotation(framework, entry.getKey(), clazz);
                    }
                }
            }

            if (handleAtmosphereAnnotation) {
                scanForCustomAnnotation(atmosphereAnnotatedClasses);
            }

            return this;
        }

        @Override
        public AnnotationProcessor scanAll() throws IOException {
            return scan("all");
        }

        @Override
        public void destroy() {
            annotations.clear();
        }
    }

    private static final class BytecodeBasedAnnotationProcessor implements AnnotationProcessor {

        protected AnnotationDetector detector;
        protected final AnnotationHandler handler;

        public BytecodeBasedAnnotationProcessor(AnnotationHandler handler) {
            this.handler = handler;
        }

        @Override
        public AnnotationProcessor configure(final AtmosphereFramework framework) {

            final AnnotationDetector.TypeReporter reporter = new AnnotationDetector.TypeReporter() {
                @SuppressWarnings("unchecked")
                @Override
                public Class<? extends Annotation>[] annotations() {
                    return handler.handledClass();
                }

                @Override
                public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
                    logger.info("Found Annotation in {} being scanned: {}", className, annotation);
                    try {
                        final Class<?> discoveredClass = loadClass(getClass(), className);
                        handler.handleAnnotation(framework, annotation, discoveredClass);
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
        public AnnotationProcessor scanAll() throws IOException {
            detector.detect();
            return this;
        }

        @Override
        public void destroy() {
            detector.destroy();
        }
    }

    private static Class<?> loadClass(Class thisClass, String className) throws Exception {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable t) {
            return thisClass.getClassLoader().loadClass(className);
        }
    }
}
