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

import java.util.Collection;

/**
 * The AtmosphereResourceSession is managed by the
 * {@link AtmosphereResourceSessionFactory}. It is created on demand by via
 * {@link AtmosphereResourceSessionFactory#getSession(AtmosphereResource, boolean)}
 * or {@link AtmosphereResourceSessionFactory#getSession(AtmosphereResource)}.
 * It allows serverside values to be stored against an
 * {@link AtmosphereResource} and lives for the lifetime of the
 * {@link AtmosphereResource}
 * 
 * @author uklance
 * 
 */
public interface AtmosphereResourceSession {
	/**
	 * Binds an object to this session, using the name specified
	 * 
	 * @param name
	 *            Attribute name
	 * @param value
	 *            Attribute value
	 * @return the previous value associated with <tt>name</tt>, or
	 *         <tt>null</tt> if there was no mapping for <tt>name</tt>
	 * @throws IllegalStateException
	 *             if this method is called on an invalidated session
	 */
	Object setAttribute(String name, Object value);

	/**
	 * Returns the object bound with the specified name in this session, or null
	 * if no object is bound under the name
	 * 
	 * @param name
	 *            Attribute name
	 * @return the object with the specified name
	 * @throws IllegalStateException
	 *             if this method is called on an invalidated session
	 */
	Object getAttribute(String name);

	/**
	 * Returns the object bound with the specified name in this session, or null
	 * if no object is bound under the name
	 * 
	 * @param name
	 *            Attribute name
	 * @param type
	 *            Attribute type
	 * @return the object with the specified name
	 * @throws IllegalStateException
	 *             if this method is called on an invalidated session
	 */
	<T> T getAttribute(String name, Class<T> type);

	/**
	 * Returns a Collection of Strings containing the names of all the objects
	 * bound to this session.
	 * 
	 * @return a Collection of Strings containing the names of all the objects
	 *         bound to this session
	 * @throws IllegalStateException
	 *             if this method is called on an invalidated session
	 */
	Collection<String> getAttributeNames();

	/**
	 * Invalidates this session then unbinds any objects bound to it.
	 * 
	 * @throws IllegalStateException
	 *             if this method is called on an invalidated session
	 */
	void invalidate();
}
