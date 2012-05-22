/*
 * Copyright 2012 Jeanfrancois Arcand
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

import java.io.File;
import java.io.IOException;

/**
 * An annotation processor for configuring the {@link AtmosphereFramework}
 *
 * @author Jeanfrancois Arcand
 */
public interface AnnotationProcessor {

    /**
     * Configure this class with an instance of {@link AtmosphereFramework}
     * @param framework
     * @return this
     */
    public AnnotationProcessor configure(AtmosphereFramework framework);

    /**
     * Scan the {@link File} looking for classe annotated with Atmosphere's Service annotation.
     *
     * @param rootDir
     * @throws IOException
     * @return this
     */
    public AnnotationProcessor scan(File rootDir) throws IOException;

}
