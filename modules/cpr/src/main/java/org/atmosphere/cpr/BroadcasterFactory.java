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

import org.atmosphere.util.LoggerUtils;

import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

/**
 * {@link Broadcaster} factory used by Atmosphere when creating broadcaster.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class BroadcasterFactory {

    protected static BroadcasterFactory factory;

    /**
     * Return an instance of the default {@link Broadcaster}
     *
     * @return an instance of the default {@link Broadcaster}
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    abstract public Broadcaster get() throws IllegalAccessException, InstantiationException;

    /**
     * Create a new instance of {@link Broadcaster} and store it for
     *
     * @param c  The {@link Broadcaster} class instance.
     * @param id The unique ID used to retrieve {@link Broadcaster}
     * @return a new instance of {@link Broadcaster}
     */
    abstract public Broadcaster get(Class<? extends Broadcaster> c, Object id) throws IllegalAccessException, InstantiationException;

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
     * @oaram id the {@link Broadcaster's ID}
     * @return false if wasn't present, or {@link Broadcaster}
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
     * @param id The Broadcaster's unique ID, or name.
     * @param createIfNull If the broadcaster is not found, create it.
     * @return a Broadcaster, or null if not found.
     */
    abstract public Broadcaster lookup(Class<? extends Broadcaster> c, Object id, boolean createIfNull);

    /**
     * Return an immutable Collection of {@link Broadcaster} this factory contains.
     * @return an immutable Collection of {@link Broadcaster} this factory contains.
     */
    abstract public Collection<Broadcaster> lookupAll();

    /**
     * Return the default {@link BroadcasterFactory}.
     *
     * @return the default {@link BroadcasterFactory}.
     */
    public synchronized static BroadcasterFactory getDefault() {
        if (factory == null) {
            Class<? extends Broadcaster> b = null;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                b = (Class<? extends Broadcaster>) cl.loadClass(AtmosphereServlet.getDefaultBroadcasterClassName());
            } catch (ClassNotFoundException e) {
                LoggerUtils.getLogger().log(Level.SEVERE,"",e);
            }
            factory = new DefaultBroadcasterFactory(b == null ? DefaultBroadcaster.class : b);

        }
        return factory;
    }
}
