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

package org.atmosphere.cpr;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A Broadcaster is responsible for delivering messages to its subscribed
 * {@link AtmosphereResource<?,?>}, which are representing a suspended response.
 * {@link AtmosphereResource<?,?>} can be added using {@link Broadcaster#addAtmosphereResource},
 * so when {@link #broadcast(java.lang.Object)} execute,
 * {@link AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent)} will
 * be invoked and the suspended connection will have a chance to write the
 * message available using {@link AtmosphereResourceEvent#getMessage()}
 * <br>
 * A {@link Broadcaster}, by default, will use an {@link ExecutorService}, and the
 * number of Thread will be computed based on the core/cpu of the OS under
 * which the application run. Thus invoking {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)} will be executed
 * <strong>asynchronously</strong> so this is important to wait for the {@link Future#get} to awake/unblock to be garantee
 * the  operation has completed.
 * <br>
 * One final word on Broadcaster: by default, a Broadcaster will broadcast using
 * all {@link AtmosphereResource<?,?>} on which the response has been suspended, e.g. {AtmosphereResource<?,?>#suspend()}
 * has been invoked. This behavior is configurable and you can configure it by invoking the
 * {@link Broadcaster#setScope(org.atmosphere.cpr.Broadcaster.SCOPE)} ):<ul>
 * <li>REQUEST: broadcast events only to the AtmosphereResourceEvent associated with the current request.</li>
 * <li>APPLICATION: broadcast events to all AtmosphereResourceEvent created for the current web application.</li>
 * <li>VM: broadcast events to all AtmosphereResourceEvent created inside the current virtual machine.</li>
 * </ul>
 *
 * @author Jeanfrancois Arcand
 */
public interface Broadcaster {

    public enum SCOPE {
        REQUEST, APPLICATION, VM
    }


    /**
     * Broadcast the {@link Object} to all suspended response, e.g. invoke
     * {@link AtmosphereHandler#onStateChange}.
     *
     * @param o and {@link Object} to be broadcasted.
     * @return a {@link Future} that can be used to synchronize using the {@link Future#get()}
     */
    public Future<Object> broadcast(Object o);

    /**
     * Delay the broadcast operation. The {@link Object} will be broadcasted
     * when the first {@link #broadcast(java.lang.Object)}
     * is invoked.
     *
     * @param o and {@link Object} to be broadcasted.
     * @return a {@link Future} that can be used to synchronize using the {@link Future#get()}
     */
    public Future<Object> delayBroadcast(Object o);

    /**
     * Delay the broadcast operation. The {@link Object} will be broadcasted once the
     * specified delay expires or when the first {@link #broadcast(java.lang.Object)}
     *
     * @param o     and {@link Object} to be broadcasted.
     * @param delay Amount of time to delay.
     * @param t     a {@link TimeUnit} of delay.
     * @return a {@link Future} that can be used to synchronize using the {@link Future#get()}
     */
    public Future<Object> delayBroadcast(Object o, long delay, TimeUnit t);

    /**
     * Broadcast periodically. The {@link Object} will be broadcasted after every period
     * specified time frame expires. If the {@link TimeUnit} is set null, the
     * {@link Object} will be broadcasted when the first {@link #broadcast(java.lang.Object)}
     * is invoked.
     *
     * @param o      and {@link Object} to be broadcasted.
     * @param period Every so often broadcast.
     * @param t      a {@link TimeUnit} of period.
     * @return a {@link Future} that can be used to synchronize using the {@link Future#get()}
     */
    public Future<?> scheduleFixedBroadcast(Object o, long period, TimeUnit t);

    /**
     * Broadcast periodically. The {@link Object} will be broadcasted after every period
     * specified time frame expires. If the {@link TimeUnit} is set null, the
     * {@link Object} will be broadcasted when the first {@link #broadcast(java.lang.Object)}      * is invoked.
     *
     * @param o       and {@link Object} to be broadcasted.
     * @param waitFor Wait for that long before first broadcast.
     * @param period  The period inbetween broadcast.
     * @param t       a {@link TimeUnit} of waitFor and period.
     * @return a {@link Future} that can be used to synchronize using the {@link Future#get()}
     */
    public Future<?> scheduleFixedBroadcast(Object o, long waitFor, long period, TimeUnit t);

    /**
     * Broadcast the {@link Object} to all suspended response, e.g. invoke
     * {@link AtmosphereHandler#onStateChange} with an instance of {@link AtmosphereResource<?,?>}, representing
     * a single suspended response..
     *
     * @param o        and {@link Object} to be broadcasted.
     * @param resource an {@link AtmosphereResource<?,?>}
     * @return a {@link Future} that can be used to synchronize using the {@link Future#get()}
     */
    public Future<Object> broadcast(Object o, AtmosphereResource<?,?> resource);

    /**
     * Broadcast the {@link Object} to all suspended response, e.g. invoke
     * {@link AtmosphereHandler#onStateChange} with a {@link Set} of  {@link AtmosphereResource<?,?>},
     * representing a set of {@link AtmosphereHandler}.
     *
     * @param o      and {@link Object} to be broadcasted.
     * @param subset a Set of {@link AtmosphereResource<?,?>}
     * @return a {@link Future} that can be used to synchronize using the {@link Future#get()}
     */
    public Future<Object> broadcast(Object o, Set<AtmosphereResource<?,?>> subset);

    /**
     * Add a {@link AtmosphereResource<?,?>} to the list of item to be notified when
     * the {@link Broadcaster#broadcast} is invoked.
     *
     * @param resource an {@link AtmosphereResource<?,?>}
     * @return {@link AtmosphereResource<?,?>} if added, or null if it was already there.
     */
    public AtmosphereResource<?,?> addAtmosphereResource(AtmosphereResource<?,?> resource);

    /**
     * Remove a {@link AtmosphereResource<?,?>} from the list of item to be notified when
     * the {@link Broadcaster#broadcast} is invoked.
     *
     * @param resource an {@link AtmosphereResource<?,?>}
     * @return {@link AtmosphereResource<?,?>} if removed, or null if it was not.
     */
    public AtmosphereResource<?,?> removeAtmosphereResource(AtmosphereResource<?,?> resource);

    /**
     * Set the {@link BroadcasterConfig} instance.
     *
     * @param bc Configuration to be set.
     */
    public void setBroadcasterConfig(BroadcasterConfig bc);

    /**
     * Return the current {@link BroadcasterConfig}
     *
     * @return the current {@link BroadcasterConfig}
     */
    public BroadcasterConfig getBroadcasterConfig();

    /**
     * Destroy this instance and shutdown it's associated {@link ExecutorService}
     */
    public void destroy();

    /**
     * Return an {@link List} of {@link AtmosphereResource<?,?>}.
     *
     * @return {@link List} of {@link AtmosphereResource<?,?>} associated with this {@link Broadcaster}.
     * @see org.atmosphere.cpr.Broadcaster#addAtmosphereResource(AtmosphereResource<?,?>)
     */
    public Collection<AtmosphereResource<?,?>> getAtmosphereResources();

    /**
     * Set the scope.
     *
     * @param scope {@link Broadcaster.SCOPE} to set.
     */
    public void setScope(SCOPE scope);

    /**
     * Return the {@link Broadcaster.SCOPE}
     *
     * @return {@link Broadcaster.SCOPE} of {@link Broadcaster}.
     */
    public SCOPE getScope();

    /**
     * Set the id of this {@link Broadcaster}
     *
     * @param name ID of this {@link Broadcaster}
     */
    public void setID(String name);

    /**
     * Return the id of this {@link Broadcaster}
     *
     * @return the id of this {@link Broadcaster}
     */
    public String getID();

    /**
     * Resume all suspended responses ({@link AtmosphereResource<?,?>}) added via
     * {@link Broadcaster#addAtmosphereResource}.
     */
    public void resumeAll();

}
