/*
 * Copyright 2012 Jason Burgess
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

import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

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
        config = new AtmosphereFramework().getAtmosphereConfig();
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
        assert factory.lookup(DefaultBroadcaster.class, id, true) != null;
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
