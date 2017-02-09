/*
 * Copyright 2017 Async-IO.org
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


package org.atmosphere.runtime;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Serialize the {@link Object} that was used as parameter to {@link Broadcaster#broadcast}.
 * <p/>
 * IMPORTANT: This class isn't supported by the Atmosphere-Jersey extension.
 *
 * @author Paul Sandoz
 * @author Jeanfrancois Arcand
 */
public interface Serializer {

    /**
     * Serialize the {@link Object} using the {@link OutputStream}.
     *
     * @param os The {@link java.io.OutputStream} to use when writing
     * @param o  The broacasted object
     * @throws IOException
     */
    void write(OutputStream os, Object o) throws IOException;
}
