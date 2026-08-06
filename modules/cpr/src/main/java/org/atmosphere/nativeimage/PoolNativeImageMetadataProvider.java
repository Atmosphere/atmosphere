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

import java.util.Collection;

/**
 * Pooled-object types, contributed only when commons-pool2 is present.
 *
 * <p>The demonstration of why {@link NativeImageMetadataProvider#isAvailable()}
 * exists: these types extend commons-pool2 interfaces, so naming them in an
 * image built without that dependency asks GraalVM to resolve a supertype that
 * is not there. Gating on the dependency keeps the metadata honest about what
 * this particular application actually contains.</p>
 */
public class PoolNativeImageMetadataProvider implements NativeImageMetadataProvider {

    @Override
    public String name() {
        return "atmosphere-runtime-pool";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.apache.commons.pool2.PooledObjectFactory",
                    false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public Collection<String> reflectiveTypes() {
        return AtmosphereReflectiveTypes.poolTypes();
    }
}
