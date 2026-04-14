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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@SuppressWarnings({"deprecation", "removal"})
class UniverseTest {

    /**
     * Reset all static fields in Universe to avoid cross-test contamination.
     */
    private void resetUniverse() throws Exception {
        for (String fieldName : new String[]{
                "factory", "framework", "resourceFactory", "sessionFactory", "metaBroadcaster"}) {
            Field f = Universe.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(null, null);
        }
        for (String fieldName : new String[]{
                "factoryDuplicate", "frameworkDuplicate", "resourceFactoryDuplicate",
                "sessionFactoryDuplicate", "metaBroadcasterDuplicate"}) {
            Field f = Universe.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(null, false);
        }
    }

    @Test
    void broadcasterFactoryRoundTrip() throws Exception {
        resetUniverse();
        var bf = mock(BroadcasterFactory.class);
        Universe.broadcasterFactory(bf);
        assertEquals(bf, Universe.broadcasterFactory());
    }

    @Test
    void broadcasterFactoryDuplicateThrows() throws Exception {
        resetUniverse();
        Universe.broadcasterFactory(mock(BroadcasterFactory.class));
        Universe.broadcasterFactory(mock(BroadcasterFactory.class));
        assertThrows(IllegalStateException.class, Universe::broadcasterFactory);
    }

    @Test
    void frameworkRoundTrip() throws Exception {
        resetUniverse();
        var fw = mock(AtmosphereFramework.class);
        Universe.framework(fw);
        assertEquals(fw, Universe.framework());
    }

    @Test
    void frameworkDuplicateThrows() throws Exception {
        resetUniverse();
        Universe.framework(mock(AtmosphereFramework.class));
        Universe.framework(mock(AtmosphereFramework.class));
        assertThrows(IllegalStateException.class, Universe::framework);
    }

    @Test
    void resourceFactoryRoundTrip() throws Exception {
        resetUniverse();
        var rf = mock(AtmosphereResourceFactory.class);
        Universe.resourceFactory(rf);
        assertEquals(rf, Universe.resourceFactory());
    }

    @Test
    void resourceFactoryDuplicateThrows() throws Exception {
        resetUniverse();
        Universe.resourceFactory(mock(AtmosphereResourceFactory.class));
        Universe.resourceFactory(mock(AtmosphereResourceFactory.class));
        assertThrows(IllegalStateException.class, Universe::resourceFactory);
    }

    @Test
    void sessionFactoryRoundTrip() throws Exception {
        resetUniverse();
        var sf = mock(AtmosphereResourceSessionFactory.class);
        Universe.sessionResourceFactory(sf);
        assertEquals(sf, Universe.sessionFactory());
    }

    @Test
    void sessionFactoryDuplicateThrows() throws Exception {
        resetUniverse();
        Universe.sessionResourceFactory(mock(AtmosphereResourceSessionFactory.class));
        Universe.sessionResourceFactory(mock(AtmosphereResourceSessionFactory.class));
        assertThrows(IllegalStateException.class, Universe::sessionFactory);
    }

    @Test
    void metaBroadcasterRoundTrip() throws Exception {
        resetUniverse();
        var mb = mock(DefaultMetaBroadcaster.class);
        Universe.metaBroadcaster(mb);
        assertEquals(mb, Universe.metaBroadcaster());
    }

    @Test
    void metaBroadcasterDuplicateThrows() throws Exception {
        resetUniverse();
        Universe.metaBroadcaster(mock(DefaultMetaBroadcaster.class));
        Universe.metaBroadcaster(mock(DefaultMetaBroadcaster.class));
        assertThrows(IllegalStateException.class, Universe::metaBroadcaster);
    }

    @Test
    void getterReturnsNullWhenNotSet() throws Exception {
        resetUniverse();
        assertNull(Universe.broadcasterFactory());
        assertNull(Universe.framework());
        assertNull(Universe.resourceFactory());
        assertNull(Universe.sessionFactory());
        assertNull(Universe.metaBroadcaster());
    }
}
