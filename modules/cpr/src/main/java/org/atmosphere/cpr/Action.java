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
 * An Action is used by {@link AtmosphereInterceptor}, {@link AsyncSupport} and {@link AtmosphereFramework} to determine
 * what to do with a request, e.g suspend it, resume it, etc.
 *
 * @author Jeanfrancois Arcand
 */
public final class Action {
    /**
     * The action's type.
     */
    public enum TYPE {
        /**
         * SUSPEND the underlying connection/response.
         * The request will be dispatched to frameworks/container via {@link AtmosphereHandler#onStateChange(AtmosphereResourceEvent)}
         */
        SUSPEND,
        /**
         * Resume the underlying connection/response.
         * The request will be dispatched to frameworks/container via {@link AtmosphereHandler#onStateChange(AtmosphereResourceEvent)}
         */
        RESUME,
        /**
         * Timeout the underlying connection/response and invoke the {@link org.atmosphere.cpr.AtmosphereResource#resume()}.
         * The request will be dispatched to frameworks/container via {@link AtmosphereHandler#onStateChange(AtmosphereResourceEvent)}
         */
        TIMEOUT,
        /**
         * Cancel the current connection/response and close it. The request will NOT be dispatched to frameworks/container via {@link AtmosphereHandler}
         */
        CANCELLED,
        /**
         * Continue the processing of the request. The request will still be dispatched to frameworks/container via {@link AtmosphereHandler}
         */
        CONTINUE,
        /**
         * Mark this action as created. The request will still be dispatched to frameworks/container via {@link AtmosphereHandler}
         */
        CREATED,
        /**
         * Mark this action as destroyed. All objects associated with this action will be candidate for being garbage collected.
         */
        DESTROYED,
        /**
         *  Skip the invocation of {@link AtmosphereHandler}, but invoke all {@link AtmosphereInterceptor}.
         */
        SKIP_ATMOSPHEREHANDLER
    }

    public final static Action CANCELLED = new Action(TYPE.CANCELLED, true);
    public final static Action CONTINUE = new Action(TYPE.CONTINUE, true);
    public final static Action CREATED = new Action(TYPE.CREATED, true);
    public final static Action RESUME = new Action(TYPE.RESUME, true);
    public final static Action SUSPEND = new Action(TYPE.SUSPEND, true);
    public final static Action DESTROYED = new Action(TYPE.DESTROYED, true);
    public final static Action SKIP_ATMOSPHEREHANDLER = new Action(TYPE.SKIP_ATMOSPHEREHANDLER);

    private long timeout;
    private TYPE type;
    private boolean immutable;

    public Action() {
        this(TYPE.CREATED);
    }

    public Action(TYPE type) {
        this(type, -1L);
    }

    public Action(TYPE type, boolean immutable) {
        this(type, -1L);
        this.immutable = immutable;
    }

    public Action(TYPE type, long timeout) {
        this.timeout = timeout;
        this.type = type;
    }

    public Action.TYPE type(){
        return type;
    }

    public Action type(Action.TYPE type){
        if (immutable) {
            throw new IllegalStateException("immutable");
        }
        this.type = type;
        return this;
    }

    public long timeout(){
        return timeout;
    }

    public Action timeout(long timeout){
        if (immutable) {
            throw new IllegalStateException("immutable");
        }

        this.timeout = timeout;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Action action = (Action) o;

        if (timeout != action.timeout()) return false;
        if (type != action.type()) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) (timeout ^ (timeout >>> 32));
        result = 31 * result + (type != null ? type.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Action{" +
                "timeout=" + timeout +
                ", type=" + type +
                '}';
    }
}
