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
package org.atmosphere.jersey;

import com.sun.jersey.api.JResponse;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.Broadcaster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * This class can be used to suspend response programmatically, similar to {@link org.atmosphere.annotation.Suspend}
 * annotation.
 * <blockquote><pre>
 *         SuspendResponse&lt;String&gt; r = new SuspendResponse.SuspendResponseBuilder&lt;String&gt;()
 *              .broadcaster(broadcaster)
 *              .outputComments(true)
 *              .period(5, TimeUnit.SECONDS)
 *              .entity("foo")
 *              .build();
 * </pre></blockquote>
 *
 * @param <E> the {@link org.atmosphere.jersey.SuspendResponse#entity type}
 * @author Jeanfrancois Arcand
 */
public class SuspendResponse<E> extends JResponse {
    private final TimeSpan suspendTimeout;
    private final Suspend.SCOPE scope;
    private final boolean outputComments;
    private final boolean resumeOnBroadcast;
    private final Collection<AtmosphereResourceEventListener> listeners;
    private final Broadcaster broadcaster;
    private final boolean writeEntity;

    protected SuspendResponse(SuspendResponseBuilder<E> b) {
        super(b);
        this.suspendTimeout = b.suspendTimeout;
        this.scope = b.scope;
        this.outputComments = b.outputComments;
        this.resumeOnBroadcast = b.resumeOnBroadcast;
        this.listeners = b.listeners;
        this.broadcaster = b.broadcaster;
        this.writeEntity = b.writeEntity;
    }

    /**
     * Return the {@link org.atmosphere.annotation.Suspend.SCOPE} value.
     *
     * @return the {@link org.atmosphere.annotation.Suspend.SCOPE} value.
     */
    public Suspend.SCOPE scope() {
        return scope;
    }

    /**
     * Return the {@link org.atmosphere.jersey.SuspendResponse.TimeSpan} used to suspend the response.
     *
     * @return the {@link org.atmosphere.jersey.SuspendResponse.TimeSpan} used to suspend the response.
     */
    public TimeSpan period() {
        return suspendTimeout;
    }

    /**
     * Tell Atmosphere to write some comments during the connection suspension.
     *
     * @return true is comment will be written.
     */
    public boolean outputComments() {
        return outputComments;
    }

    /**
     * Resume the connection on the first {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)} operations.
     *
     * @return true if the connection needs to be resumed.
     */
    public boolean resumeOnBroadcast() {
        return resumeOnBroadcast;
    }

    /**
     * Write the returned entity back to the calling connection. Default is false.
     */
    public boolean writeEntity() {
        return writeEntity;
    }

    /**
     * Return the {@link Broadcaster} that will be used to broadcast events.
     *
     * @return the {@link Broadcaster} that will be used to broadcast events.
     */
    public Broadcaster broadcaster() {
        return broadcaster;
    }

    /**
     * Return the current list of {@link AtmosphereResourceEventListener} classes.
     *
     * @return the current list of {@link AtmosphereResourceEventListener} classes.
     */
    public Collection<AtmosphereResourceEventListener> listeners() {
        return Collections.unmodifiableCollection(listeners);
    }

    /**
     * A Builder for {@link org.atmosphere.jersey.SuspendResponse}
     *
     * @param <E>
     */
    public static class SuspendResponseBuilder<E> extends AJResponseBuilder<E, SuspendResponseBuilder<E>> {

        protected TimeSpan suspendTimeout = new TimeSpan(-1, TimeUnit.MILLISECONDS);
        protected Suspend.SCOPE scope = Suspend.SCOPE.APPLICATION;
        protected boolean outputComments = true;
        protected boolean resumeOnBroadcast = false;
        protected final Collection<AtmosphereResourceEventListener> listeners
                = new ArrayList<AtmosphereResourceEventListener>();
        private Broadcaster broadcaster;
        private boolean writeEntity = true;

        /**
         * Default constructor.
         */
        public SuspendResponseBuilder() {
        }

        /**
         * Construct a shallow copy. The metadata map will be copied but not the
         * key/value references.
         *
         * @param that the AJResponseBuilder to copy from.
         */
        public SuspendResponseBuilder(SuspendResponseBuilder<E> that) {
            super(that);
        }

        /**
         * Set the {@link org.atmosphere.annotation.Suspend.SCOPE} value
         *
         * @param scope {@link org.atmosphere.annotation.Suspend.SCOPE} value
         * @return this
         */
        public SuspendResponseBuilder<E> scope(Suspend.SCOPE scope) {
            this.scope = scope;
            return this;
        }

        /**
         * Set the timeout period.
         *
         * @param suspendTimeout the period
         * @param timeUnit       the {@link java.util.concurrent.TimeUnit}
         * @return this
         */
        public SuspendResponseBuilder<E> period(int suspendTimeout, TimeUnit timeUnit) {
            this.suspendTimeout = new TimeSpan(suspendTimeout, timeUnit);
            return this;
        }

        /**
         * Set true to tell Atmosphere to write comments when suspending.
         *
         * @param outputComments true to tell Atmosphere to write comments when suspending
         * @return this
         */
        public SuspendResponseBuilder<E> outputComments(boolean outputComments) {
            this.outputComments = outputComments;
            return this;
        }

        /**
         * Set to true to resume the connection on the first {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)}
         *
         * @param resumeOnBroadcast true to resume the connection on the first {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)}
         * @return this
         */
        public SuspendResponseBuilder<E> resumeOnBroadcast(boolean resumeOnBroadcast) {
            this.resumeOnBroadcast = resumeOnBroadcast;
            return this;
        }

        /**
         * Set the {@link Broadcaster}
         *
         * @param broadcaster {@link Broadcaster}
         * @return this
         */
        public SuspendResponseBuilder<E> broadcaster(Broadcaster broadcaster) {
            this.broadcaster = broadcaster;
            return this;
        }

        /**
         * Write the returned entity back to the calling connection. Default is false.
         */
        public SuspendResponseBuilder<E> writeEntity(boolean writeEntity) {
            this.writeEntity = writeEntity;
            return this;
        }

        /**
         * Add {@link org.atmosphere.cpr.AtmosphereResourceEventListener}
         *
         * @param e {@link org.atmosphere.cpr.AtmosphereResourceEventListener}
         * @return this
         */
        public SuspendResponseBuilder<E> addListener(AtmosphereResourceEventListener e) {
            listeners.add(e);
            return this;
        }

        /**
         * Build the {@link org.atmosphere.jersey.SuspendResponse}
         *
         * @return an instance of {@link org.atmosphere.jersey.SuspendResponse}
         */
        public SuspendResponse<E> build() {
            SuspendResponse<E> r = new SuspendResponse<E>(this);
            reset();
            return r;
        }
    }

    /**
     * Util class that encapsulate a period and a TimeUnit.
     */
    public static class TimeSpan {

        private final TimeUnit timeUnit;
        private final int period;

        public TimeSpan(int period, TimeUnit timeUnit) {
            this.period = period;
            this.timeUnit = timeUnit;
        }

        public int value() {
            return period;
        }

        public TimeUnit timeUnit() {
            return timeUnit;
        }
    }
}
