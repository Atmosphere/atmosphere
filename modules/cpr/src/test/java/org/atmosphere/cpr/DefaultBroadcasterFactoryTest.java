/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2011 Jason Burgess. All rights reserved.
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

import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;

import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link DefaultBroadcasterFactory}.
 * 
 * @author Jason Burgess
 */
public class DefaultBroadcasterFactoryTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeMethod
    public void setUp() throws Exception {
        config = mock(AtmosphereConfig.class);
        factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
    }

    @Test
    public void testGet_0args() {
        Broadcaster result = factory.get();
        assert result != null;
        assert result instanceof DefaultBroadcaster;
    }

    @Test
    public void testGet_Object() {
        String id = "id";
        Broadcaster result = factory.get(id);
        assert result != null;
        assert result instanceof DefaultBroadcaster;
        assert id.equals(result.getID());
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testGet_Object_Twice() {
        String id = "id";
        factory.get(id);
        factory.get(id);
    }

    @Test
    public void testAdd() {
        String id = "id";
        String id2 = "foo";
        Broadcaster b = factory.get(id);
        assert factory.add(b, id) == false;
        assert factory.lookup(id) != null;
        assert factory.add(b, id2) == true;
        assert factory.lookup(id2) != null;
    }

    @Test
    public void testRemove() {
        String id = "id";
        String id2 = "foo";
        Broadcaster b = factory.get(id);
        Broadcaster b2 = factory.get(id2);
        assert factory.remove(b, id2) == false;
        assert factory.remove(b2, id) == false;
        assert factory.remove(b, id) == true;
        assert factory.lookup(id) == null;
    }

    @Test
    public void testLookup_Class_Object() {
        String id = "id";
        String id2 = "foo";
        assert factory.lookup(DefaultBroadcaster.class, id) != null;
        assert factory.lookup(DefaultBroadcaster.class, id2) == null;
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testLookup_Class_Object_BadClass() {
        String id = "id";
        factory.get(id);
        factory.lookup(SimpleBroadcaster.class, id);
    }

    @Test
    public void testLookup_Object() {
        String id = "id";
        String id2 = "foo";
        factory.get(id);
        assert factory.lookup(id) != null;
        assert factory.lookup(id2) == null;
    }

    @Test
    public void testLookup_Object_boolean() {
        String id = "id";
        assert factory.lookup(id, false) == null;
        Broadcaster b = factory.lookup(id, true);
        assert b != null;
        assert id.equals(b.getID());
    }

    @Test
    public void testLookup_Class_Object_boolean() {
        String id = "id";
        assert factory.lookup(DefaultBroadcaster.class, id, false) == null;
        Broadcaster b = factory.lookup(DefaultBroadcaster.class, id, true);
        assert b != null;
        assert b instanceof DefaultBroadcaster;
        assert id.equals(b.getID());
    }
}
