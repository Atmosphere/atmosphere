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

import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the processor through a real {@code javac} invocation.
 *
 * <p>Asserting the processor's fields would prove nothing: the thing that has to
 * work is {@code javac} discovering it, invoking it, and a usable index landing
 * in the output directory. That is the entire mechanism by which a native image
 * finds annotated classes on a build with no Spring AOT and no Quarkus
 * augmentation, so it is compiled here rather than described.</p>
 */
class AtmosphereAnnotationIndexProcessorTest {

    /** Source held in memory so the test needs no fixture files on disk. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static Path compileWithProcessor(String className, String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null,
                "no system Java compiler — this test needs a JDK, not a JRE");

        var output = Files.createTempDirectory("atmo-apt");
        var classpath = System.getProperty("java.class.path");

        var task = compiler.getTask(
                null, null, null,
                List.of("-d", output.toString(), "-classpath", classpath, "-proc:full"),
                null,
                List.<JavaFileObject>of(new InMemorySource(className, source)));
        task.setProcessors(List.of(new AtmosphereAnnotationIndexProcessor()));

        assertTrue(task.call(), "fixture failed to compile");
        return output;
    }

    @Test
    void anAnnotatedClassIsRecordedInTheIndex() throws Exception {
        var output = compileWithProcessor("fixture.ChatEndpoint", """
                package fixture;

                import org.atmosphere.config.service.ManagedService;

                @ManagedService(path = "/fixture")
                public class ChatEndpoint {
                }
                """);

        var index = output.resolve(AtmosphereAnnotationIndexProcessor.INDEX_RESOURCE);
        assertTrue(Files.isRegularFile(index),
                "javac must emit " + AtmosphereAnnotationIndexProcessor.INDEX_RESOURCE
                        + " — without it a native image has no way to find this endpoint");

        var content = Files.readString(index, StandardCharsets.UTF_8);
        assertTrue(content.contains("fixture.ChatEndpoint"),
                "the annotated class must be listed; got:\n" + content);
    }

    @Test
    void aClassWithNoAtmosphereAnnotationProducesNoIndex() throws Exception {
        var output = compileWithProcessor("fixture.Plain", """
                package fixture;

                public class Plain {
                }
                """);

        var index = output.resolve(AtmosphereAnnotationIndexProcessor.INDEX_RESOURCE);
        assertFalse(Files.exists(index),
                "an artifact with no annotated classes must emit no index at all. An empty "
                        + "file would read as an authoritative \"nothing here\" and could mask "
                        + "a real index shipped by another jar on the classpath");
    }

    @Test
    void theIndexIsSortedSoBuildsAreReproducible() throws Exception {
        var output = compileWithProcessor("fixture.Two", """
                package fixture;

                import org.atmosphere.config.service.ManagedService;

                public class Two {
                    @ManagedService(path = "/z")
                    public static class Zeta { }

                    @ManagedService(path = "/a")
                    public static class Alpha { }
                }
                """);

        var lines = Files.readAllLines(
                        output.resolve(AtmosphereAnnotationIndexProcessor.INDEX_RESOURCE))
                .stream().filter(l -> !l.startsWith("#") && !l.isBlank()).toList();

        assertTrue(lines.size() >= 2, "both nested endpoints should be indexed: " + lines);
        assertTrue(lines.equals(lines.stream().sorted().toList()),
                "the index is an input to a native image; unsorted output would make two "
                        + "builds of identical source differ: " + lines);
    }

    @Test
    void theProcessorCoversTheFrameworksOwnAnnotationList() {
        var supported = new AtmosphereAnnotationIndexProcessor().getSupportedAnnotationTypes();

        assertFalse(supported.isEmpty(), "an empty set would index nothing, silently");
        assertTrue(supported.contains("org.atmosphere.config.service.ManagedService"),
                "@ManagedService is the annotation most endpoints use; it must be indexed");
        assertTrue(supported.contains("org.atmosphere.config.service.WebSocketHandlerService"),
                "the supported set is derived from AtmosphereAnnotations so it cannot drift "
                        + "from what the runtime looks for");
    }
}
