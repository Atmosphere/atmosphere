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

/**
 * Add lifecycle method to the {@link BroadcastFilter} interface.
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcastFilterLifecycle extends BroadcastFilter {

    /**
     * Initialize the {@link BroadcastFilter}.
     */
    void init(AtmosphereConfig config);

    /**
     * Destroy this {@link BroadcastFilter} and its internal resources.
     */
    void destroy();
}