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

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.AtmosphereAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the classes carrying Atmosphere annotations, so the framework can be
 * handed a ready-made map instead of scanning for itself.
 *
 * <p>Discovery reads {@code .class} files off the classpath. That is fine on the
 * JVM and impossible in a GraalVM native image, where those files no longer
 * exist — the scan returns nothing, and because "found nothing" is
 * indistinguishable from "there is nothing", the application starts happily and
 * serves no annotated endpoint at all.</p>
 *
 * <p>The fix is to move the scan to build time. {@link #scan} runs during Spring
 * AOT processing (on a JVM, with a real classpath); the result is written to
 * {@value #PRECOMPUTED_RESOURCE} and read back by {@link #readPrecomputed} at
 * runtime. On the JVM nothing changes if the file is absent — the scan simply
 * runs as before.</p>
 *
 * @see AtmosphereAnnotationScanAotProcessor
 */
final class AtmosphereAnnotationScanner {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAnnotationScanner.class);

    /**
     * Where the build-time result lands. Read through
     * {@link PathMatchingResourcePatternResolver} rather than
     * {@code ClassLoader.getResource} so a jar-packaged application and an
     * exploded one behave the same.
     */
    static final String PRECOMPUTED_RESOURCE = "META-INF/atmosphere/annotated-classes.txt";

    /** Source of truth for which annotations matter — shared with the Quarkus indexer. */
    private static final List<Class<? extends Annotation>> CORE_ANNOTATIONS =
            AtmosphereAnnotations.coreAnnotations();

    private AtmosphereAnnotationScanner() {
    }

    /**
     * Scan the classpath for annotated classes. Only usable where {@code .class}
     * files exist: on the JVM, or during AOT processing.
     *
     * @param packagesProperty comma-separated user packages ({@code atmosphere.packages}), may be {@code null}
     * @return the annotated classes, in a stable order so the generated file is reproducible
     */
    static Set<Class<?>> scan(String packagesProperty) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        for (var annotation : CORE_ANNOTATIONS) {
            scanner.addIncludeFilter(new AnnotationTypeFilter(annotation));
        }

        Set<Class<?>> classes = new HashSet<>();

        // Framework-provided @AtmosphereAnnotation processors live across modules
        // (org.atmosphere.annotation, org.atmosphere.ai.processor, …), so the root
        // package is scanned rather than an enumerated list.
        scanPackage(scanner, "org.atmosphere", classes);

        var userPackages = new ArrayList<String>();
        if (packagesProperty != null) {
            for (var pkg : packagesProperty.split(",")) {
                var trimmed = pkg.trim();
                if (!trimmed.isEmpty()) {
                    userPackages.add(trimmed);
                    scanPackage(scanner, trimmed, classes);
                }
            }
        }

        // Second pass: a @AtmosphereAnnotation processor declares the annotation it
        // handles, and extensions define their own. Those custom types are only
        // known once the first pass has loaded the processors.
        var customAnnotationTypes = new HashSet<Class<? extends Annotation>>();
        for (var clazz : classes) {
            var aa = clazz.getAnnotation(AtmosphereAnnotation.class);
            if (aa != null && !CORE_ANNOTATIONS.contains(aa.value())) {
                customAnnotationTypes.add(aa.value());
            }
        }

        if (!customAnnotationTypes.isEmpty()) {
            var customScanner = new ClassPathScanningCandidateComponentProvider(false);
            for (var customAnnotation : customAnnotationTypes) {
                customScanner.addIncludeFilter(new AnnotationTypeFilter(customAnnotation));
            }
            for (var pkg : userPackages) {
                scanPackage(customScanner, pkg, classes);
            }
            logger.debug("Discovered {} custom Atmosphere annotation types: {}",
                    customAnnotationTypes.size(), customAnnotationTypes);
        }

        // Sorted: the AOT-generated file is an input to the native image, and a
        // set's iteration order would make otherwise-identical builds differ.
        var ordered = new LinkedHashSet<Class<?>>();
        classes.stream()
                .sorted(java.util.Comparator.comparing(Class::getName))
                .forEach(ordered::add);
        return ordered;
    }

    private static void scanPackage(ClassPathScanningCandidateComponentProvider scanner,
                                    String packageName, Set<Class<?>> classes) {
        for (BeanDefinition bd : scanner.findCandidateComponents(packageName)) {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                logger.warn("Could not load Atmosphere annotated class: {}", bd.getBeanClassName(), e);
            }
        }
    }

    /**
     * Read the class list recorded at build time.
     *
     * @return the classes, or empty when no build-time scan ran (the normal JVM case)
     */
    static Optional<Set<Class<?>>> readPrecomputed(ClassLoader classLoader) {
        var resolver = new PathMatchingResourcePatternResolver(classLoader);

        org.springframework.core.io.Resource[] resources;
        try {
            // classpath*: — every jar contributes the index for the classes it
            // contains, and they are merged. Reading a single resource would let
            // whichever jar happened to be first silently hide the rest, which
            // for an application jar shadowing the framework's own index means
            // losing every built-in annotation processor.
            resources = resolver.getResources("classpath*:" + PRECOMPUTED_RESOURCE);
        } catch (IOException e) {
            logger.warn("Could not enumerate {} — falling back to a classpath scan: {}",
                    PRECOMPUTED_RESOURCE, e.toString());
            return Optional.empty();
        }

        if (resources.length == 0) {
            return Optional.empty();
        }

        var classes = new LinkedHashSet<Class<?>>();
        var missing = new ArrayList<String>();
        try {
            for (var resource : resources) {
                try (var reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        var name = line.trim();
                        if (name.isEmpty() || name.startsWith("#")) {
                            continue;
                        }
                        try {
                            classes.add(Class.forName(name, false, classLoader));
                        } catch (ClassNotFoundException | NoClassDefFoundError e) {
                            // An optional module present at build time but absent at
                            // run time. Skipping keeps the remaining endpoints working;
                            // failing startup would be a worse trade for an endpoint
                            // the application may not even use.
                            missing.add(name);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read {} — falling back to a classpath scan: {}",
                    PRECOMPUTED_RESOURCE, e.toString());
            return Optional.empty();
        }

        if (!missing.isEmpty()) {
            logger.warn("{} build-time annotated class(es) are not on the runtime classpath "
                    + "and were skipped: {}", missing.size(), missing);
        }

        // An empty file means the build-time scan genuinely found nothing. That is
        // reported as a present-but-empty result rather than "absent", so the
        // runtime does not fall back to a scan that cannot work here anyway.
        logger.info("Atmosphere using {} annotated class(es) recorded at build time "
                + "across {} index file(s)", classes.size(), resources.length);
        return Optional.of(classes);
    }

    /** Group classes by each annotation they carry — the shape the framework consumes. */
    static Map<Class<? extends Annotation>, Set<Class<?>>> toAnnotationMap(Set<Class<?>> classes) {
        Map<Class<? extends Annotation>, Set<Class<?>>> classesByAnnotation = new HashMap<>();
        for (var clazz : classes) {
            for (Annotation annotation : clazz.getAnnotations()) {
                classesByAnnotation
                        .computeIfAbsent(annotation.annotationType(), k -> new HashSet<>())
                        .add(clazz);
            }
        }
        return classesByAnnotation;
    }
}
