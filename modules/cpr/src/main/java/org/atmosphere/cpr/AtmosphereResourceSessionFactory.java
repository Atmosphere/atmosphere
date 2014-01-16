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

/**
 * Factory for {@link AtmosphereResourceSession} instances
 * 
 * @author uklance
 */
public abstract class AtmosphereResourceSessionFactory {
	// TODO: support IOC
	private static final AtmosphereResourceSessionFactory DEFAULT = new DefaultAtmosphereResourceSessionFactory();

	/**
	 * Return the default {@link AtmosphereResourceSessionFactory}
	 * 
	 * @return the default {@link AtmosphereResourceSessionFactory}
	 */
	public static AtmosphereResourceSessionFactory getDefault() {
		return DEFAULT;
	}

	/**
	 * Returns the current session associated with the
	 * {@link AtmosphereResource} or, if there is no current session and create
	 * is true, returns a new session.
	 * 
	 * If create is false and the request has no valid HttpSession, this method
	 * returns null.
	 * 
	 * @param resource
	 *            An {@link AtmosphereResource}
	 * @param create
	 *            true to create a new session if necessary; false to return
	 *            null if there's no current session
	 * @return the session associated with this request or null if create is
	 *         false and the resource has no valid session
	 */
	public abstract AtmosphereResourceSession getSession(AtmosphereResource resource, boolean create);

	/**
	 * Returns the current session associated with the
	 * {@link AtmosphereResource}, or creates one if it does not yet exist.
	 * 
	 * @param resource
	 *            An {@link AtmosphereResource}
	 * @return the current session associated with the
	 *         {@link AtmosphereResource}, or creates one if it does not yet
	 *         exist.
	 */
	public AtmosphereResourceSession getSession(AtmosphereResource resource) {
		return getSession(resource, true);
	}
}
