/*
 * Copyright 2014 Jeanfrancois Arcand
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.fail;

import java.util.Collections;

import org.mockito.Mockito;
import org.testng.annotations.Test;

public class DefaultAtmosphereResourceSessionFactoryTest {

	@Test
	public void testSessionLifecycle() {
		DefaultAtmosphereResourceSessionFactory factory = (DefaultAtmosphereResourceSessionFactory) AtmosphereResourceSessionFactory
				.getDefault();
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
