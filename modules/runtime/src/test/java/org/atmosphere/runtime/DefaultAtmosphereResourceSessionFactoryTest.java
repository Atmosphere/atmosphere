/*
 * Copyright 2017 Async-IO.org
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
package org.atmosphere.runtime;

import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.fail;

/**
 * @author uklance (https://github.com/uklance)
 */
public class DefaultAtmosphereResourceSessionFactoryTest {
    @Test
    public void testSessionLifecycle() {
        DefaultAtmosphereResourceSessionFactory factory = new DefaultAtmosphereResourceSessionFactory();

        AtmosphereResourceEventListener disconnectListener = factory.getDisconnectListener();

        AtmosphereResource r1 = Mockito.mock(AtmosphereResource.class);
        when(r1.uuid()).thenReturn("uuid1");

        AtmosphereResource r2 = Mockito.mock(AtmosphereResource.class);
        when(r2.uuid()).thenReturn("uuid2");

        AtmosphereResourceSession s1 = factory.getSession(r1, false);
        assertNull(s1);
        s1 = factory.getSession(r1);
        assertNotNull(s1);

        assertSame(s1, factory.getSession(r1));

        AtmosphereResourceSession s2 = factory.getSession(r2);
        assertNotSame(s1, s2);

        s1.setAttribute("att1", "s1v1");
        assertEquals("s1v1", s1.getAttribute("att1", String.class));
        assertEquals(Collections.singleton("att1"), s1.getAttributeNames());

        s2.setAttribute("att1", "s2v1");
        assertEquals("s2v1", s2.getAttribute("att1", String.class));

        s1.setAttribute("att1", "s1v2");
        assertEquals("s1v2", s1.getAttribute("att1"));

        verify(r1).addEventListener(disconnectListener);
        verify(r2).addEventListener(disconnectListener);

        AtmosphereResourceEvent e1 = mock(AtmosphereResourceEvent.class);
        when(e1.getResource()).thenReturn(r1);

        AtmosphereResourceEvent e2 = mock(AtmosphereResourceEvent.class);
        when(e2.getResource()).thenReturn(r2);

        disconnectListener.onDisconnect(e1);
        disconnectListener.onDisconnect(e2);

        try {
            s1.getAttribute("foo");
            fail();
        } catch (IllegalStateException e) {
        }

        try {
            s1.setAttribute("foo", "bar");
            fail();
        } catch (IllegalStateException e) {
        }

        try {
            s1.getAttributeNames();
            fail();
        } catch (IllegalStateException e) {
        }

        assertNull(factory.getSession(r1, false));
        assertNull(factory.getSession(r2, false));
    }

}
