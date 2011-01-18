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
/*
 * Copyright 2009 Richard Zschech.
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
package org.atmosphere.gwt.server;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;


/**
 * A Comet response provides methods for sending messages using the associated HTTP response. It also provides methods
 * for setting up Comet sessions.
 * 
 * @author Richard Zschech
 */
public interface GwtResponseWriter {
	
	/**
	 * Write a single message to the associated HTTP response.
	 * 
	 * @param message
	 * @throws IOException
	 */
	public void write(Serializable message) throws IOException;
	
	/**
	 * Write a single message to the associated HTTP response. Flush the HTTP output stream if flush is true.
	 * 
	 * @param message
	 * @param flush
	 * @throws IOException
	 */
	public void write(Serializable message, boolean flush) throws IOException;
	
	/**
	 * Write a list of message to the associated HTTP response. This method may be more optimal to the single message
	 * version.
	 * 
	 * @param messages
	 * @throws IOException
	 */
	public void write(List<? extends Serializable> messages) throws IOException;
	
	/**
	 * Write a list of message to the associated HTTP response. This method may be more optimal to the single message
	 * version. Flush the HTTP output stream if flush is true.
	 * 
	 * @param messages
	 * @param flush
	 * @throws IOException
	 */
	public void write(List<? extends Serializable> messages, boolean flush) throws IOException;
	
	/**
	 * Write a heartbeat message to the associated HTTP response.
	 * 
	 * @throws IOException
	 */
	public void heartbeat() throws IOException;
	
	/**
	 * Write a terminate message to the associated HTTP response and close the HTTP output stream/
	 * 
	 * @throws IOException
	 */
	public void terminate() throws IOException;
	
	/**
	 * Test if this Comet response has been terminated by calling the {@link #terminate()} method or terminated from the
	 * HTTP client disconnecting.
	 * 
	 * @return if this Comet response has been terminated
	 */
	public boolean isTerminated();

    /**
     * Returns the last time a write has been performed
     * @return
     */
    public long getLastWriteTime();
    
	/**
	 * Send an error before the response is sent.
	 * @param statusCode
	 * @throws IOException
	 */
	public void sendError(int statusCode) throws IOException;
	
	/**
	 * Send an error before the response is sent.
	 * @param statusCode
	 * @param message
	 * @throws IOException
	 */
	public void sendError(int statusCode, String message) throws IOException;
}
