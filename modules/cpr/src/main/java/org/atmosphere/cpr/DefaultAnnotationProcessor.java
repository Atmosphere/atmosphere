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
package org.atmosphere.cpr;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.util.IOUtils;
import org.atmosphere.util.annotation.AnnotationDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.HandlesTypes;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.util.IOUtils.loadClass;

/**
 * An {@link AnnotationProcessor} that selects between a ServletContextInitializer based scanner, and
 * a bytecode based scanner based on <a href="https://github.com/rmuller/infomas-asl"></a>.
 * <p/>
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultAnnotationProcessor implements AnnotationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAnnotationProcessor.class);

    /**
     * The attribute name under which the annotations are stored in the servlet context.
     */
    public static final String ANNOTATION_ATTRIBUTE = "org.atmosphere.cpr.ANNOTATION_MAP";

    // Source of truth: AtmosphereAnnotations.coreAnnotations()
    private static final List<Class<? extends Annotation>> coreAnnotations =
            AtmosphereAnnotations.coreAnnotations();

    private AnnotationProcessor delegate;
    private final AnnotationHandler handler;
    private final AtomicBoolean coreAnnotationsFound = new AtomicBoolean();
    private AtmosphereFramework framework;

    private final AnnotationDetector.TypeReporter atmosphereReporter = new AnnotationDetector.TypeReporter() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override
        public Class<? extends Annotation>[] annotations() {
            return new Class[]{
                    AtmosphereAnnotation.class
            };
        }

        @Override
        public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
            try {
                coreAnnotationsFound.set(true);
                handler.handleProcessor(loadClass(getClass(), className));
            } catch (Exception e) {
                logger.warn("Error scanning @AtmosphereAnnotation", e);
            }
        }
    };

    public DefaultAnnotationProcessor() {
        this.handler = new AnnotationHandler();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void configure(final AtmosphereConfig config) {
        ServletContext sc = config.framework().getServletContext();

        Map<Class<? extends Annotation>, Set<Class<?>>> annotations =
                (Map<Class<? extends Annotation>, Set<Class<?>>>) sc.getAttribute(ANNOTATION_ATTRIBUTE);
        sc.removeAttribute(ANNOTATION_ATTRIBUTE);

        boolean useByteCodeProcessor = config.getInitParameter(ApplicationConfig.BYTECODE_PROCESSOR, false);

        boolean scanForAtmosphereAnnotation = false;
        if (useByteCodeProcessor || annotations == null || annotations.isEmpty()) {
            delegate = new BytecodeBasedAnnotationProcessor(handler);
            scanForAtmosphereAnnotation = true;
        } else {
            var clone = new HashMap<>(annotations);
            delegate = new ServletContainerInitializerAnnotationProcessor(handler, clone, config.framework());
        }
        logger.info("AnnotationProcessor {} being used", delegate.getClass());

        if (scanForAtmosphereAnnotation) {
            scanForAnnotation(config.framework());
        }

        this.framework = config.framework();
        delegate.configure(config.framework().getAtmosphereConfig());
    }

    private void scanForAnnotation(AtmosphereFramework f) {
        List<String> packages = f.customAnnotationPackages();
        AnnotationDetector detector = new AnnotationDetector(atmosphereReporter);
        try {
            if (!packages.isEmpty()) {
                for (String p : packages) {
                    logger.trace("Package {} scanned for @AtmosphereAnnotation", p);
                    detector.detect(p);
                }
            }

            // Now look for application defined annotation
            String path = IOUtils.realPath(f.getServletContext(), f.getHandlersPath());
            if (path != null) {
                detector.detect(new File(path));
            }

            String pathLibs =  IOUtils.realPath(f.getServletContext(), f.getLibPath());
            if (pathLibs != null) {
                var libFolder = new File(pathLibs);
                File[] jars = libFolder.listFiles((arg0, arg1) -> arg1.endsWith(".jar"));

                if (jars != null) {
                    for (File file : jars) {
                        detector.detect(file);
                    }
                }
            }

            // JBoss|vfs with APR issue, or any strange containers may fail. This is a hack for them.
            // https://github.com/Atmosphere/atmosphere/issues/1292
            if (!coreAnnotationsFound.get()) {
                fallbackToManualAnnotatedClasses(getClass(), f, handler);
            }
        } catch (IOException e) {
            logger.warn("Unable to scan annotation", e);
        } finally {
            detector.destroy();
        }
    }

    private static void fallbackToManualAnnotatedClasses(Class<?> mainClass, AtmosphereFramework f, AnnotationHandler handler) {
        logger.warn("Unable to detect annotations. Application may fail to deploy.");
        f.annotationScanned(true);
        for (Class<?> a : coreAnnotations) {
            try {
                handler.handleProcessor(loadClass(mainClass, a.getName()));
            } catch (Exception e) {
                logger.trace("", e);
            }
        }
    }


    @Override
    public AnnotationProcessor scan(final File rootDir) throws IOException {
        delegate.scan(rootDir);
        handler.processDeferred(framework);
        return this;
    }

    @Override
    public AnnotationProcessor scan(final String packageName) throws IOException {
        delegate.scan(packageName);
        handler.processDeferred(framework);
        return this;
    }

    @Override
    public AnnotationProcessor scanAll() throws IOException {
        delegate.scanAll();
        handler.processDeferred(framework);
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
        private boolean alreadyScanned;

        private ServletContainerInitializerAnnotationProcessor(AnnotationHandler handler,
                                                               final Map<Class<? extends Annotation>, Set<Class<?>>> annotations,
                                                               final AtmosphereFramework framework) {
            this.annotations = annotations;
            this.framework = framework;
            this.handler = handler;
        }

        @Override
        public void configure(final AtmosphereConfig config) {
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
            handler.processDeferred(framework);
            return this;
        }

        private boolean handleAtmosphereAnnotation(Set<Class<?>> atmosphereAnnotatedClasses) {
            boolean scanForCustomizedAnnotation = false;
            if (atmosphereAnnotatedClasses != null) {
                for (Class<?> clazz : atmosphereAnnotatedClasses) {
                    handler.handleProcessor(clazz);
                }
            } else {
                fallbackToManualAnnotatedClasses(getClass(),framework, handler);
            }

            // If larger, a custom annotation has been defined.
            if (atmosphereAnnotatedClasses != null && atmosphereAnnotatedClasses.size() >=
                    AnnotationScanningServletContainerInitializer.class.getAnnotation(HandlesTypes.class).value().length) {
                scanForCustomizedAnnotation = true;
            }
            return scanForCustomizedAnnotation;
        }

        private void scanForCustomAnnotation(Set<Class<?>> atmosphereAnnotatedClasses) throws IOException {

            handler.flushCoreAnnotations(atmosphereAnnotatedClasses);

            BytecodeBasedAnnotationProcessor b = new BytecodeBasedAnnotationProcessor(handler);
            b.configure(framework.getAtmosphereConfig());
            String path = framework.getServletContext().getRealPath(framework.getHandlersPath());
            if (path != null) {
                b.scan(new File(path)).destroy();
            } else {
                logger.warn("Unable to scan using File. Scanning classpath");
                b.scanAll();
            }
        }

        @Override
        public AnnotationProcessor scan(final String packageName) throws IOException {
            Set<Class<?>> atmosphereAnnotatedClasses = annotations.get(AtmosphereAnnotation.class);

            if (packageName.equals("all") || getClass().getClassLoader().getResource(packageName.replace(".", "/")) != null) {
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
                handler.processDeferred(framework);
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

        private AnnotationDetector detector;
        private final AnnotationHandler handler;
        private AtmosphereFramework framework;

        public BytecodeBasedAnnotationProcessor(AnnotationHandler handler) {
            this.handler = handler;
        }

        @Override
        public void configure(final AtmosphereConfig config) {
            this.framework = config.framework();

            final AnnotationDetector.TypeReporter reporter = new AnnotationDetector.TypeReporter() {
                @Override
                public Class<? extends Annotation>[] annotations() {
                    return handler.handledClass();
                }

                @Override
                public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
                    try {
                        final Class<?> discoveredClass = loadClass(getClass(), className);
                        handler.handleAnnotation(config.framework(), annotation, discoveredClass);
                    } catch (Exception e) {
                        logger.warn("Could not load discovered class", e);
                    }
                }

            };
            detector = new AnnotationDetector(reporter);
        }

        @Override
        public AnnotationProcessor scan(File rootDir) throws IOException {
            detector.detect(rootDir);
            handler.processDeferred(framework);
            return this;
        }

        @Override
        public AnnotationProcessor scan(String packageName) throws IOException {
            logger.trace("Scanning @Service annotations in {}", packageName);
            detector.detect(packageName);
            handler.processDeferred(framework);
            return this;
        }

        @Override
        public AnnotationProcessor scanAll() throws IOException {
            detector.detect();
            handler.processDeferred(framework);
            return this;
        }

        @Override
        public void destroy() {
            if (detector != null) detector.destroy();
        }
    }

}
