/*
 * Copyright 2013 Jeanfrancois Arcand
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
 * A listener that can be used to track {@link AsyncSupport} events like SUSPEND, RESUME, TIMEOUT, DESTROYED and CLOSED
 *
 * @author Jeanfrancois Arcand
 */
public interface AsyncSupportListener {

    /**
     * Invoked when an {@link AtmosphereResource} gets suspended.
     *
     * @param request  an {@link AtmosphereRequest}
     * @param response an {@link AtmosphereResponse}
     */
    void onSuspend(AtmosphereRequest request, AtmosphereResponse response);

    /**
     * Invoked when an {@link AtmosphereResource} gets resumed.
     *
     * @param request  an {@link AtmosphereRequest}
     * @param response an {@link AtmosphereResponse}
     */
    void onResume(AtmosphereRequest request, AtmosphereResponse response);

    /**
     * Invoked when an {@link AtmosphereResource} times out.
     *
     * @param request  an {@link AtmosphereRequest}
     * @param response an {@link AtmosphereResponse}
     */
    void onTimeout(AtmosphereRequest request, AtmosphereResponse response);

    /**
     * Invoked when an {@link AtmosphereResource} gets closed.
     *
     * @param request  an {@link AtmosphereRequest}
     * @param response an {@link AtmosphereResponse}
     */
    void onClose(AtmosphereRequest request, AtmosphereResponse response);

    /**
     * Invoked when an {@link AtmosphereResource} gets destroyed.
     *
     * @param request  an {@link AtmosphereRequest}
     * @param response an {@link AtmosphereResponse}
     */
    void onDestroyed(AtmosphereRequest request, AtmosphereResponse response);
}
