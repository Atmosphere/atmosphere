package org.atmosphere.cpr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.Mockito;

public class DefaultAtmosphereResourceSessionFactoryTest {

	@Test
	public void testSessionLifecycle() {
		DefaultAtmosphereResourceSessionFactory factory = (DefaultAtmosphereResourceSessionFactory) AtmosphereResourceSessionFactory.getDefault();
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
		
		s1.setAttribute("att1", "value1");
		s2.setAttribute("att1", "value2");
		
		assertEquals("value1", s1.getAttribute("att1", String.class));
		assertEquals("value2", s2.getAttribute("att1", String.class));
		
		s1.setAttribute("att1", "value1.1");
		assertEquals("value1.1", s1.getAttribute("att1"));
		
		verify(r1).addEventListener(disconnectListener);
		verify(r2).addEventListener(disconnectListener);
		
		AtmosphereResourceEvent e1 = mock(AtmosphereResourceEvent.class);
		when(e1.getResource()).thenReturn(r1);
		
		AtmosphereResourceEvent e2 = mock(AtmosphereResourceEvent.class);
		when(e2.getResource()).thenReturn(r2);
		
		disconnectListener.onDisconnect(e1);
		disconnectListener.onDisconnect(e2);
		
		assertNull(factory.getSession(r1, false));
		assertNull(factory.getSession(r2, false));
	}

}
