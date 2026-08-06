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
package org.atmosphere.nativeimage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The merged view of every {@link NativeImageMetadataProvider} on the classpath.
 *
 * <p>One place asks the question, so every consumer gets the same answer: the
 * Spring Boot hints registrar, the Quarkus build step, and the generator that
 * embeds GraalVM metadata into the shipped jars. Before this existed the same
 * hand-written list was copied into three integration modules and no extension
 * module could contribute at all — a shape where the Quarkus and Spring answers
 * could drift apart, and where a plain servlet deployment got nothing.</p>
 *
 * <p>Discovery is a union: providers cannot remove each other's entries, only
 * add. Registering a type that is never used costs image size; failing to
 * register one that is used costs a runtime failure, usually a silent one. The
 * asymmetry is the reason this errs toward inclusion.</p>
 */
public final class NativeImageMetadata {

    private static final Logger logger = LoggerFactory.getLogger(NativeImageMetadata.class);

    private final List<String> reflectiveTypes;
    private final List<String> resourcePatterns;
    private final List<String> providerNames;

    private NativeImageMetadata(List<String> reflectiveTypes,
                                List<String> resourcePatterns,
                                List<String> providerNames) {
        this.reflectiveTypes = List.copyOf(reflectiveTypes);
        this.resourcePatterns = List.copyOf(resourcePatterns);
        this.providerNames = List.copyOf(providerNames);
    }

    /** Collect using the class loader that loaded this class. */
    public static NativeImageMetadata collect() {
        return collect(NativeImageMetadata.class.getClassLoader());
    }

    /**
     * Collect from every available provider visible to {@code classLoader}.
     *
     * <p>A provider that throws while loading is skipped with a warning rather
     * than aborting: it is one module's metadata, and losing it should not stop
     * an image build or a startup that would otherwise succeed. The skip is
     * logged at WARN because the consequence — some types quietly unregistered —
     * is exactly the kind of thing that must not pass unnoticed.</p>
     *
     * @param classLoader loader to search, may be {@code null} for the system loader
     * @return the merged metadata, never {@code null}
     */
    public static NativeImageMetadata collect(ClassLoader classLoader) {
        var types = new LinkedHashSet<String>();
        var resources = new LinkedHashSet<String>();
        var names = new ArrayList<String>();

        for (var provider : load(classLoader)) {
            try {
                if (!provider.isAvailable()) {
                    logger.debug("Native metadata provider {} reports unavailable — skipping",
                            provider.name());
                    continue;
                }
                addAll(types, provider.reflectiveTypes(), provider, "reflectiveTypes");
                addAll(resources, provider.resourcePatterns(), provider, "resourcePatterns");
                names.add(provider.name());
            } catch (RuntimeException | LinkageError e) {
                logger.warn("Native metadata provider {} failed and was skipped — types it "
                                + "declares will not be registered: {}",
                        provider.getClass().getName(), e.toString());
            }
        }

        logger.debug("Native metadata: {} type(s), {} resource pattern(s) from provider(s) {}",
                types.size(), resources.size(), names);

        return new NativeImageMetadata(new ArrayList<>(types), new ArrayList<>(resources), names);
    }

    private static void addAll(Set<String> sink, java.util.Collection<String> values,
                               NativeImageMetadataProvider provider, String what) {
        if (values == null) {
            logger.warn("Native metadata provider {} returned null {} — treating as empty",
                    provider.name(), what);
            return;
        }
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                sink.add(value.trim());
            }
        }
    }

    /** Providers in priority order, highest first, ties broken by name for stable output. */
    private static List<NativeImageMetadataProvider> load(ClassLoader classLoader) {
        try {
            var found = new ArrayList<NativeImageMetadataProvider>();
            var loader = classLoader == null
                    ? ServiceLoader.load(NativeImageMetadataProvider.class)
                    : ServiceLoader.load(NativeImageMetadataProvider.class, classLoader);
            for (var provider : loader) {
                found.add(provider);
            }
            found.sort(Comparator.comparingInt(NativeImageMetadataProvider::priority).reversed()
                    .thenComparing(NativeImageMetadataProvider::name));
            return found;
        } catch (ServiceConfigurationError e) {
            logger.warn("Could not load native metadata providers — no types will be "
                    + "registered from the SPI: {}", e.toString());
            return List.of();
        }
    }

    /** Type names to register for reflection, de-duplicated, in provider order. */
    public List<String> reflectiveTypes() {
        return reflectiveTypes;
    }

    /** Resource patterns to include in the image, de-duplicated, in provider order. */
    public List<String> resourcePatterns() {
        return resourcePatterns;
    }

    /** Names of the providers that contributed, for logging and generated-file provenance. */
    public List<String> providerNames() {
        return providerNames;
    }
}
