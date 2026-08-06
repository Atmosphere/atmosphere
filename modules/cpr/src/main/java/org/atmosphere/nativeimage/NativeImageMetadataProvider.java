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

import java.util.Collection;
import java.util.List;

/**
 * Lets a module declare what it needs at runtime that a native image cannot
 * infer — the classes it constructs by name, and the resources it reads.
 *
 * <p>Ahead-of-time compilation keeps only what it can prove reachable. A class
 * named in a configuration string and loaded with {@code IOUtils.loadClass} is
 * reachable from nothing, so it is dropped, and the first hint of trouble is a
 * {@code ClassNotFoundException} at runtime — often swallowed, as it was for
 * broadcaster caches, where the result was every annotated endpoint silently
 * failing to register.</p>
 *
 * <p>Modules implement this and register it in
 * {@code META-INF/services/org.atmosphere.nativeimage.NativeImageMetadataProvider}.
 * The framework merges every provider it finds ({@link NativeImageMetadata}),
 * which matters because {@code atmosphere-ai}, {@code atmosphere-mcp},
 * {@code atmosphere-agent} and others all load classes by name and none of them
 * can be described from {@code atmosphere-runtime}. A module that adds a
 * reflective lookup declares it next to the code that does the lookup, rather
 * than in a central list nobody remembers to update.</p>
 *
 * <p>Consumers are the Spring Boot hints registrar, the Quarkus build step, and
 * the generated GraalVM metadata embedded in each jar. The last of those means
 * a plain servlet deployment with no integration module still gets everything —
 * see {@code META-INF/native-image} in {@code atmosphere-runtime}.</p>
 *
 * <p>Implementations must be side-effect free and cheap to construct: they are
 * instantiated during image build and during startup, before the framework
 * exists.</p>
 */
public interface NativeImageMetadataProvider {

    /** Identifier for logs and the generated metadata's provenance comment. */
    String name();

    /**
     * Fully-qualified names of types that are constructed, inspected or invoked
     * reflectively — typically anything selected by an init-param or read from a
     * {@code ServiceLoader} file.
     *
     * <p>Names rather than {@link Class} literals: a provider must be able to
     * declare a type from an optional dependency without forcing that dependency
     * to be present, which is the same reason the framework's own
     * {@code AtmosphereReflectiveTypes} is a list of strings.</p>
     *
     * @return type names, never {@code null}
     */
    default Collection<String> reflectiveTypes() {
        return List.of();
    }

    /**
     * Resource patterns that must survive into the image — {@code ServiceLoader}
     * files above all, since a missing one turns a discovered implementation into
     * a silently absent one.
     *
     * @return patterns understood by GraalVM's resource configuration, never {@code null}
     */
    default Collection<String> resourcePatterns() {
        return List.of();
    }

    /**
     * Whether this provider applies to the current classpath. A provider covering
     * an optional dependency returns {@code false} when that dependency is absent,
     * so its types are not registered into an image that cannot contain them.
     *
     * @return {@code true} to include this provider's metadata
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Ordering hint; higher runs first. Only affects the order metadata is
     * emitted, never whether it is emitted — registration is a union, so no
     * provider can suppress another's types.
     *
     * @return the priority, default {@code 0}
     */
    default int priority() {
        return 0;
    }
}
