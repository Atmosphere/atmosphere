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

import org.atmosphere.inject.AtmosphereConfigAware;

/**
 * Intercept the dispatch of {@link AtmosphereResource} before they get dispatched to {@link AtmosphereHandler}s.
 * An implementation of this class can intercept the dispatch and modify the AtmosphereResource and its
 * associated {@link AtmosphereRequest} and {@link AtmosphereResponse}.
 * <p/>
 * This class can be used to implement custom protocols like Server-Sent Events, Socket.IO, etc.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereInterceptor extends AtmosphereConfigAware {

    /**
     * Invoked before an {@link AtmosphereResource} gets dispatched to {@link AtmosphereHandler}.
     *
     * @param r a {@link AtmosphereResource}
     * @return {@link Action#CONTINUE} or {@link Action#SUSPEND}
     *         to dispatch the {@link AtmosphereResource} to other {@link AtmosphereInterceptor} or {@link AtmosphereHandler}.
     *         Return {@link Action.TYPE#CANCELLED} to stop the processing.
     */
    Action inspect(AtmosphereResource r);

    /**
     * Invoked after an {@link AtmosphereResource} gets dispatched to {@link AtmosphereHandler}.
     *
     * @param r a {@link AtmosphereResource}
     */
    void postInspect(AtmosphereResource r);

    /**
     * Clean the AtmosphereInterceptor when removed or when the Atmosphere is undeployed.
     */
    void destroy();
}
