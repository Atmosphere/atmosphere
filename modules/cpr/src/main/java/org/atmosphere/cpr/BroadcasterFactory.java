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
 */
package org.atmosphere.cpr;

import java.util.Collection;

/**
 * {@link Broadcaster} factory used by Atmosphere when creating broadcaster.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class BroadcasterFactory {

    protected static BroadcasterFactory factory;
    protected static AtmosphereServlet.AtmosphereConfig config;

    /**
     * Return an instance of the default {@link Broadcaster} The name of the Broadcaster will be randmly generated.
     *
     * @return an instance of the default {@link Broadcaster}
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    abstract public Broadcaster get();

    /**
     * Create a new instance of {@link Broadcaster} and store it for
     *
     * @param id The unique ID used to retrieve {@link Broadcaster}
     * @return a new instance of {@link Broadcaster}
     */
    abstract public Broadcaster get(Object id);

    /**
     * Create a new instance of {@link Broadcaster} and store it for
     *
     * @param c  The {@link Broadcaster} class instance.
     * @param id The unique ID used to retrieve {@link Broadcaster}
     * @return a new instance of {@link Broadcaster}
     */
    abstract public Broadcaster get(Class<? extends Broadcaster> c, Object id);

    /**
     * Shutdown all {@link Broadcaster}
     */
    abstract public void destroy();

    /**
     * Add a {@link Broadcaster} to the list.
     *
     * @param b a {@link Broadcaster}
     * @return false if a with the same name {@link Broadcaster} was already stored
     */
    abstract public boolean add(Broadcaster b, Object id);

    /**
     * Remove a {@link Broadcaster} to the list.
     *
     * @param b a {@link Broadcaster}
     * @return false if wasn't present, or {@link Broadcaster}
     * @param id the {@link Broadcaster's ID}
     */
    abstract public boolean remove(Broadcaster b, Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     * used when invoking {@link BroadcasterFactory#getDefault()}
     *
     * @param c
     * @param id The Broadcaster's unique ID, or name.
     * @return a Broadcaster, or null if not found.
     */
    abstract public Broadcaster lookup(Class<? extends Broadcaster> c, Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     * used when invoking {@link BroadcasterFactory#getDefault()}
     *
     * @param c
     * @param id           The Broadcaster's unique ID, or name.
     * @param createIfNull If the broadcaster is not found, create it.
     * @return a Broadcaster, or null if not found.
     */
    abstract public Broadcaster lookup(Class<? extends Broadcaster> c, Object id, boolean createIfNull);

     /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     * used when invoking {@link BroadcasterFactory#getDefault()}
     *
     * @param id The Broadcaster's unique ID, or name.
     * @return a Broadcaster, or null if not found.
     */
    abstract public Broadcaster lookup(Object id);

    /**
     * Lookup a {@link Broadcaster} instance using {@link Broadcaster#getID()} or ID
     * used when invoking {@link BroadcasterFactory#getDefault()}
     *
     * @param id           The Broadcaster's unique ID, or name.
     * @param createIfNull If the broadcaster is not found, create it.
     * @return a Broadcaster, or null if not found.
     */
    abstract public Broadcaster lookup(Object id, boolean createIfNull);


    /**
     * Remove all instance of {@link AtmosphereResource} from all registered {@link Broadcaster}
     *
     * @param r an void {@link AtmosphereResource}
     */
    abstract public void removeAllAtmosphereResource(AtmosphereResource<?, ?> r);

    /**
     * Return an immutable Collection of {@link Broadcaster} this factory contains.
     *
     * @return an immutable Collection of {@link Broadcaster} this factory contains.
     */
    abstract public Collection<Broadcaster> lookupAll();

    /**
     * Return the default {@link BroadcasterFactory}.
     *
     * @return the default {@link BroadcasterFactory}.
     */
    public synchronized static BroadcasterFactory getDefault() {
        return factory;
    }

    static void setBroadcasterFactory(BroadcasterFactory f, AtmosphereServlet.AtmosphereConfig c) {
        factory = f;
        config = c;
    }
}
