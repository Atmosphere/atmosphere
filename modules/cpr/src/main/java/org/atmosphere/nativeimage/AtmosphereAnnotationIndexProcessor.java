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

import org.atmosphere.cpr.AtmosphereAnnotations;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Records Atmosphere-annotated classes at compile time, so they can be found in
 * a native image where scanning for them is impossible.
 *
 * <p>Discovery normally reads {@code .class} files off the classpath. A GraalVM
 * image has none, so the scan returns nothing — and an empty result is
 * indistinguishable from an application that genuinely has no endpoints, which
 * is why this failed silently for six months rather than loudly on day one.</p>
 *
 * <p>{@code javac} already knows every annotated type it compiles. Writing that
 * list to {@value #INDEX_RESOURCE} moves discovery to the one moment it cannot
 * fail, and does so for any build: Maven or Gradle, Spring Boot or Quarkus or a
 * bare servlet container. It needs no Spring AOT and no Quarkus augmentation,
 * which is the point — those covered two deployments and left the rest with
 * nothing.</p>
 *
 * <p>The processor is registered in
 * {@code META-INF/services/javax.annotation.processing.Processor}, so
 * {@code javac} picks it up from the classpath automatically. Having
 * {@code atmosphere-runtime} as a dependency is the entire setup.</p>
 *
 * <p>Every jar ships the index for the classes it contains, and the runtime
 * merges them — see {@code AtmosphereAnnotationScanner}. One shared file would
 * mean the last jar on the classpath silently hiding the others.</p>
 */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class AtmosphereAnnotationIndexProcessor extends AbstractProcessor {

    /** Where each jar records the annotated classes it contains. */
    public static final String INDEX_RESOURCE = "META-INF/atmosphere/annotated-classes.txt";

    /** Sorted so the emitted file is byte-identical across builds of the same source. */
    private final Set<String> discovered = new TreeSet<>();

    /**
     * The annotations to index, taken from the framework's own list so this
     * cannot drift from what the runtime looks for.
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return AtmosphereAnnotations.coreAnnotations().stream()
                .map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                // Only types are registered as handlers. Method-level annotations
                // (@Ready, @Message) are found by reflecting over the type once it
                // has been discovered, so indexing their enclosing class is enough.
                if (element instanceof TypeElement type) {
                    discovered.add(type.getQualifiedName().toString());
                }
            }
        }

        if (roundEnv.processingOver()) {
            writeIndex();
        }

        // Never claim the annotations: other processors — Quarkus, Micronaut, an
        // application's own — must still see them.
        return false;
    }

    private void writeIndex() {
        if (discovered.isEmpty()) {
            // Nothing to record. Writing an empty file would be worse than writing
            // none: the runtime treats a present-but-empty index as an authoritative
            // "there is nothing here", which would mask a real index from another jar.
            return;
        }

        try {
            var file = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", INDEX_RESOURCE);
            try (Writer writer = file.openWriter()) {
                writer.write("# Atmosphere-annotated classes in this artifact, recorded at\n");
                writer.write("# compile time. A native image cannot scan for them.\n");
                for (String type : discovered) {
                    writer.write(type);
                    writer.write('\n');
                }
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Atmosphere indexed " + discovered.size()
                            + " annotated class(es) for ahead-of-time discovery");
        } catch (IOException e) {
            // A warning, not an error: failing the compile would be a poor trade for
            // a JVM build, which does not need this file at all. Native builds that
            // do need it will fail visibly at startup instead — and the message here
            // is what points at the cause.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Atmosphere could not write " + INDEX_RESOURCE + " (" + e.getMessage()
                            + "). Native images built from this artifact will not discover "
                            + "its annotated classes.");
        }
    }
}
