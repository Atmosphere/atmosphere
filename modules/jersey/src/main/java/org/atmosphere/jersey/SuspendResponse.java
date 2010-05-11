/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.jersey;

import com.sun.jersey.api.JResponse;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.Broadcaster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * This class can be used to suspend response programmatically, similar to {@link org.atmosphere.annotation.Suspend}
 * annotation.
 * {@code}
 *
 * @param <E> the {@link org.atmosphere.jersey.SuspendResponse#entity type}
 * 
 * @author Jeanfrancois Arcand
 */
public class SuspendResponse<E> extends JResponse {
    private final TimeSpan suspendTimeout;
    private final Suspend.SCOPE scope;
    private final boolean outputComments;
    private final boolean resumeOnBroadcast;
    private final Collection<AtmosphereResourceEventListener> listeners;
    private final Broadcaster broadcaster;

    protected SuspendResponse(SuspendResponseBuilder<E> b) {
        super(b);
        this.suspendTimeout = b.suspendTimeout;
        this.scope = b.scope;
        this.outputComments = b.outputComments;
        this.resumeOnBroadcast = b.resumeOnBroadcast;
        this.listeners = b.listeners;
        this.broadcaster = b.broadcaster;
    }

    /**
     * Return the {@link org.atmosphere.annotation.Suspend.SCOPE} value.
     * @return the {@link org.atmosphere.annotation.Suspend.SCOPE} value.
     */
    public Suspend.SCOPE scope() {
        return scope;
    }

    /**
     * Return the {@link org.atmosphere.jersey.SuspendResponse.TimeSpan} used to suspend the response.
     * @return the {@link org.atmosphere.jersey.SuspendResponse.TimeSpan} used to suspend the response.
     */
    public TimeSpan period() {
        return suspendTimeout;
    }

    /**
     * Tell Atmosphere to write some comments during the connection suspension.
     * @return true is comment will be written.
     */
    public boolean outputComments() {
        return outputComments;
    }

    /**
     * Resume the connection on the first {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)} operations.
     * @return true if the connection needs to be resumed.
     */
    public boolean resumeOnBroadcast() {
        return resumeOnBroadcast;
    }

    /**
     * Return the {@link Broadcaster} that will be used to broadcast events.
     * @return the {@link Broadcaster} that will be used to broadcast events.
     */
    public Broadcaster broadcaster() {
        return broadcaster;
    }

    /**
     * Return the current list of {@link AtmosphereResourceEventListener} classes.
     * @return the current list of {@link AtmosphereResourceEventListener} classes.
     */
    public Collection<AtmosphereResourceEventListener> listeners() {
        return Collections.unmodifiableCollection(listeners);
    }

    /**
     * A Builder for {@link org.atmosphere.jersey.SuspendResponse}
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
         * @param scope {@link org.atmosphere.annotation.Suspend.SCOPE} value
         * @return this
         */
        public SuspendResponseBuilder<E> scope(Suspend.SCOPE scope) {
            this.scope = scope;
            return this;
        }

        /**
         * Set the timeout period.
         * @param suspendTimeout the period
         * @param timeUnit the {@link java.util.concurrent.TimeUnit}
         * @return this
         */
        public SuspendResponseBuilder<E> period(int suspendTimeout, TimeUnit timeUnit) {
            this.suspendTimeout = new TimeSpan(suspendTimeout,timeUnit);
            return this;
        }

        /**
         * Set true to tell Atmosphere to write comments when suspending.
         * @param outputComments true to tell Atmosphere to write comments when suspending
         * @return this
         */
        public SuspendResponseBuilder<E> outputComments(boolean outputComments) {
            this.outputComments = outputComments;
            return this;
        }

        /**
         * Set to true to resume the connection on the first {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)}
         * @param resumeOnBroadcast true to resume the connection on the first {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)}
         * @return this
         */
        public SuspendResponseBuilder<E> resumeOnBroadcast(boolean resumeOnBroadcast) {
            this.resumeOnBroadcast = resumeOnBroadcast;
            return this;
        }

        /**
         * Set the {@link Broadcaster}
         * @param broadcaster  {@link Broadcaster}
         * @return this
         */
        public SuspendResponseBuilder<E> broadcaster(Broadcaster broadcaster) {
            this.broadcaster = broadcaster;
            return this;
        }

        /**
         * Add {@link org.atmosphere.cpr.AtmosphereResourceEventListener}
         * @param e {@link org.atmosphere.cpr.AtmosphereResourceEventListener}
         * @return this
         */
        public SuspendResponseBuilder<E> addListener(AtmosphereResourceEventListener e) {
            listeners.add(e);
            return this;
        }

        /**
         * Build the {@link org.atmosphere.jersey.SuspendResponse}
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
