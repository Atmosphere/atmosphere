/*
 * Copyright 2008-2020 Async-IO.org
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

import org.atmosphere.inject.AtmosphereConfigAware;

import java.io.File;
import java.io.IOException;

/**
 * An annotation processor for configuring the {@link AtmosphereFramework}.
 *
 * @author Jeanfrancois Arcand
 */
public interface AnnotationProcessor extends AtmosphereConfigAware{

    /**
     * Scan the {@link File} looking for classes annotated with Atmosphere's Service annotation.
     *
     * @param rootDir a directory where annotation can possibly be defined
     * @throws IOException
     * @return this
     */
     AnnotationProcessor scan(File rootDir) throws IOException;

    /**
     * Scan the package looking for classes annotated with Atmosphere's Service annotation.
     *
     * @param packageName package name
     * @return this
     */
     AnnotationProcessor scan(String packageName) throws IOException;

    /**
     * Scan all classes on the classpath looking for classes annotated with Atmosphere's Service annotation.
     *
     * @return this
     */
     AnnotationProcessor scanAll() throws IOException;

    /**
     * Destroy all resources associated with this object. Once destroyed, this object can no longer be used.
     */
     void destroy();
}
