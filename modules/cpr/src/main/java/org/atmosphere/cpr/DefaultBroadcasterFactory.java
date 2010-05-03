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


import org.atmosphere.util.LoggerUtils;

import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * This class is responsible for creating {@link Broadcaster} instance. You can also add and remove {@link Broadcaster}
 * and lookup using {@link BroadcasterFactory#getDefault()} ()}
 * from any Classes loaded using the same class loader.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultBroadcasterFactory extends BroadcasterFactory {

    private final ConcurrentHashMap<Object, Broadcaster> store
            = new ConcurrentHashMap<Object, Broadcaster>();

    private static BroadcasterFactory factory;

    private final Class<? extends Broadcaster> clazz;

    private final BroadcasterConfig config;

    protected DefaultBroadcasterFactory(Class<? extends Broadcaster> clazz, BroadcasterConfig config) {
        this.clazz = clazz;
        this.config = config;
        
        if (factory == null) {
            this.factory = this;
        }
    }
                       
    /**
     * {@inheritDoc}
     */
    public Broadcaster get() throws IllegalAccessException, InstantiationException {
        Broadcaster b = clazz.newInstance();
        b.setBroadcasterConfig(config);
        b.setID(clazz.getSimpleName() + "-" + new Random().nextInt());
        store.put(b.getID(), b);
        return b;
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster get(Class<? extends Broadcaster> c, Object id) throws IllegalAccessException, InstantiationException {

        if (id == null) throw new NullPointerException("id is null");
        if (c == null) throw new NullPointerException("Class is null");

        Broadcaster b = c.newInstance();
        b.setBroadcasterConfig(config);
        b.setID(id.toString());

        store.put(id, b);
        return b;
    }

    /**
     * Return a {@link Broadcaster} based on its name.
     *
     * @param name The unique ID
     * @return a {@link Broadcaster}, or null
     */
    private Broadcaster getBroadcaster(Object name) {
        return store.get(name);
    }

    /**
     * {@inheritDoc}
     */
    public boolean add(Broadcaster b, Object id) {
        return (store.put(id, b) == null);
    }

    /**
     * {@inheritDoc}
     */
    public boolean remove(Broadcaster b, Object id) {
        return (store.remove(id) != null);
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster lookup(Class<? extends Broadcaster> c, Object id) {
        return lookup(c,id,false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster lookup(Class<? extends Broadcaster> c, Object id, boolean createIfNull) {
        Broadcaster b = getBroadcaster(id);
        if (b != null && b.getScope() == Broadcaster.SCOPE.REQUEST) {
            throw new IllegalStateException("Broadcaster " + b
                    + " cannot be looked up as its scope is REQUEST");
        }

        if (b != null && !c.isAssignableFrom(b.getClass())) {
            String em = "Invalid lookup class " + c.getName() + ". Cached class is: " + b.getClass().getName();
            if (LoggerUtils.getLogger().isLoggable(Level.FINE)) {
                LoggerUtils.getLogger().log(Level.FINE, em);
            }
            throw new IllegalStateException(em);
        }

        if (b == null && createIfNull) {
            try {
                b = get(c,id);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        return b;
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        Enumeration<Broadcaster> e = store.elements();
        while (e.hasMoreElements()) {
            e.nextElement().destroy();
        }
        store.clear();
        factory = null;
    }

    /**
     * Build a {@link BroadcasterFactory}
     *
     * @param clazz  A class implementing {@link Broadcaster}
     * @param config An instance of {@link BroadcasterConfig}
     * @return
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    public static BroadcasterFactory build(Class<? extends Broadcaster> clazz, BroadcasterConfig config)
            throws InstantiationException, IllegalAccessException {
        return new DefaultBroadcasterFactory(clazz, config);
    }

    /**
     * Build a default {@link BroadcasterFactory} returned when invoking {@link #getDefault()} ()}.
     *
     * @param clazz  A class implementing {@link Broadcaster}
     * @param config An instance of {@link BroadcasterConfig}
     * @return the default {@link BroadcasterFactory}.
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    public static BroadcasterFactory buildAndReplaceDefaultfactory(Class<? extends Broadcaster> clazz, BroadcasterConfig config)
            throws InstantiationException, IllegalAccessException {

        factory = new DefaultBroadcasterFactory(clazz, config);
        return factory;
    }

}
