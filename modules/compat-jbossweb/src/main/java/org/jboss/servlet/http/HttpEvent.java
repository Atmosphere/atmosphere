/*
 * Copyright 2011 Jeanfrancois Arcand
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
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2008 Sun Microsystems, Inc. All rights reserved.
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
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.jboss.servlet.http;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The HttpEvent interface.
 *
 * @author Remy Maucherat
 */
public interface HttpEvent {

    public enum EventType {BEGIN, READ, END, ERROR, TIMEOUT}
    
        
    /**
     * Returns the HttpServletRequest.
     * 
     * @return HttpServletRequest
     */
    public HttpServletRequest getHttpServletRequest();
    
    /**
     * Returns the HttpServletResponse.
     * 
     * @return HttpServletResponse
     */
    public HttpServletResponse getHttpServletResponse();
    
    /**
     * Returns the event type.
     * 
     * @return EventType
     */
    public EventType getType();
    
    
    /**
     * Ends the Comet session. This signals to the container that 
     * the container wants to end the comet session. This will send back to the
     * client a notice that the server has no more data to send as part of this
     * request. The servlet should perform any needed cleanup as if it had recieved
     * an END or ERROR event. 
     * 
     * @throws IOException if an IO exception occurs
     */
    public void close() throws IOException;
    
    /**
     * Sets the timeout for this Comet connection. Please NOTE, that the implementation 
     * of a per connection timeout is OPTIONAL and MAY NOT be implemented.<br/>
     * This method sets the timeout in milliseconds of idle time on the connection.
     * The timeout is reset every time data is received from the connection or data is flushed
     * using <code>response.flushBuffer()</code>. If a timeout occurs, the 
     * <code>error(HttpServletRequest, HttpServletResponse)</code> method is invoked. The 
     * web application SHOULD NOT attempt to reuse the request and response objects after a timeout
     * as the <code>error(HttpServletRequest, HttpServletResponse)</code> method indicates.<br/>
     * This method should not be called asynchronously, as that will have no effect.
     * 
     * @param timeout The timeout in milliseconds for this connection, must be a positive value, larger than 0
     * @throws IOException An IOException may be thrown to indicate an IO error, 
     *         or that the EOF has been reached on the connection
     * @throws ServletException An exception has occurred, as specified by the root
     *         cause
     * @throws UnsupportedOperationException if per connection timeout is not supported, either at all or at this phase
     *         of the invocation.
     */
    public void setTimeout(int timeout)
        throws IOException, ServletException, UnsupportedOperationException;

}
