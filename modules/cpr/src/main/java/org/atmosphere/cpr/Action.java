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
package org.atmosphere.cpr;

/**
 * An Action is used by {@link AtmosphereInterceptor}, {@link AsyncSupport} and {@link AtmosphereFramework} to determine
 * what to do with a request, e.g suspend it, resume it, etc.
 *
 * @author Jeanfrancois Arcand
 */
public record Action(TYPE type, long timeout) {
    /**
     * The action's type.
     */
    public enum TYPE {
        /**
         * SUSPEND the underlying connection/response.
         * The request will be dispatched to framework/container via {@link AtmosphereHandler#onStateChange(AtmosphereResourceEvent)}
         */
        SUSPEND,
        /**
         * Resume the underlying connection/response.
         * The request will be dispatched to framework/container via {@link AtmosphereHandler#onStateChange(AtmosphereResourceEvent)}
         */
        RESUME,
        /**
         * Timeout the underlying connection/response and invoke the {@link org.atmosphere.cpr.AtmosphereResource#resume()}.
         * The request will be dispatched to framework/container via {@link AtmosphereHandler#onStateChange(AtmosphereResourceEvent)}
         */
        TIMEOUT,
        /**
         * Cancel the current connection/response and close it. The request will NOT be dispatched to
         * framework/container via {@link AtmosphereHandler}
         */
        CANCELLED,
        /**
         * Continue the processing of the request. The request will still be dispatched to framework/container via {@link AtmosphereHandler}
         */
        CONTINUE,
        /**
         * Mark this action as created. The request will still be dispatched to framework/container via {@link AtmosphereHandler}
         */
        CREATED,
        /**
         * Mark this action as destroyed. All objects associated with this action will be candidate for being garbage collected.
         */
        DESTROYED,
        /**
         * Fake suspend for WebSocket Message
         */
        SUSPEND_MESSAGE,
        /**
         * Skip the invocation of {@link AtmosphereHandler} and interrupt the invocation of all {@link AtmosphereInterceptor}.
         */
        SKIP_ATMOSPHEREHANDLER
    }

    public static final Action CANCELLED = new Action(TYPE.CANCELLED);
    public static final Action CONTINUE = new Action(TYPE.CONTINUE);
    public static final Action CREATED = new Action(TYPE.CREATED);
    public static final Action RESUME = new Action(TYPE.RESUME);
    public static final Action SUSPEND = new Action(TYPE.SUSPEND);
    public static final Action DESTROYED = new Action(TYPE.DESTROYED);
    public static final Action SKIP_ATMOSPHEREHANDLER = new Action(TYPE.SKIP_ATMOSPHEREHANDLER);

    public Action(TYPE type) {
        this(type, -1L);
    }

    public Action() {
        this(TYPE.CREATED);
    }
}
