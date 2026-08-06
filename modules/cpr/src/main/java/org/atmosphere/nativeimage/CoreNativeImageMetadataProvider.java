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

import org.atmosphere.cpr.AtmosphereReflectiveTypes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What {@code atmosphere-runtime} itself needs in a native image.
 *
 * <p>Covers the framework's own by-name lookups: broadcasters and their caches,
 * the default interceptor chain, annotation processors, websocket protocols and
 * async-support implementations — all selected through init-params and loaded
 * with {@code IOUtils.loadClass}, none of them reachable by static analysis.</p>
 *
 * <p>The {@code ServiceLoader} files matter as much as the classes. {@code
 * Injectable} implementations are discovered rather than referenced, so if the
 * file is dropped from the image the framework finds nothing to inject and
 * reports no error — the same silent shape as the bugs this SPI exists to
 * prevent.</p>
 */
public class CoreNativeImageMetadataProvider implements NativeImageMetadataProvider {

    @Override
    public String name() {
        return "atmosphere-runtime";
    }

    /** Runs first so the framework's own types lead the generated metadata. */
    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public Collection<String> reflectiveTypes() {
        var types = new ArrayList<String>();
        types.addAll(AtmosphereReflectiveTypes.coreTypes());
        types.addAll(AtmosphereReflectiveTypes.annotationProcessors());
        return types;
    }

    @Override
    public Collection<String> resourcePatterns() {
        return List.of(
                "META-INF/services/org.atmosphere.inject.Injectable",
                "META-INF/services/org.atmosphere.inject.CDIProducer",
                "META-INF/services/org.atmosphere.nativeimage.NativeImageMetadataProvider");
    }
}
