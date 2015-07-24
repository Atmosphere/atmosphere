/*
 * Copyright 2015 Async-IO.org
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

/**
 * Listener for when {@libk AtmosphereResource} gets suspended and disconnected.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResourceListener {

    /**
     * Ibnvoked when the {@link org.atmosphere.cpr.AtmosphereResource} gets suspended
     *
     * @param uuid {@link AtmosphereResource#uuid()}
     */
    void onSuspended(String uuid);

    /**
     * Ibnvoked when the {@link org.atmosphere.cpr.AtmosphereResource} gets disconnected
     *
     * @param uuid {@link AtmosphereResource#uuid()}
     */
    void onDisconnect(String uuid);
}
