/*
 * Copyright 2013 Jeanfrancois Arcand
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

package org.atmosphere.jersey;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple placeholder that can be used to broadcast message using a specific
 * {@link Broadcaster}.
 *
 * @author Jeanfrancois Arcand
 */
public class Broadcastable {

    private static final Logger logger = LoggerFactory.getLogger(Broadcastable.class);

    private final Object message;
    private final Broadcaster b;
    private final Object callerMessage;

    public Broadcastable(Broadcaster b) {
        this.b = b;
        message = "";
        callerMessage = "";
    }

    /**
     * Broadcast the <tt>message</tt> to the set of suspended {@link AtmosphereResource}, and write back
     * the <tt>message</tt> to the request which invoked the current resource method.
     *
     * @param message the message which will be broadcasted
     * @param b       the {@link Broadcaster}
     */
    public Broadcastable(Object message, Broadcaster b) {
        this.b = b;
        this.message = message;
        callerMessage = message;
    }

    /**
     * Broadcast the <tt>message</tt> to the set of suspended {@link AtmosphereResource}, and write back
     * the <tt>callerMessage</tt> to the request which invoked the current resource method.
     *
     * @param message       the message which will be broadcasted
     * @param callerMessage the message which will be sent back to the request.
     * @param b             the {@link Broadcaster}
     */
    public Broadcastable(Object message, Object callerMessage, Broadcaster b) {
        this.b = b;
        this.message = message;
        this.callerMessage = callerMessage;
        if (callerMessage == null) {
            throw new NullPointerException("callerMessage cannot be null");
        }
    }

    /**
     * Broadcast the message.
     *
     * @return the transformed message ({@link BroadcastFilter})
     */
    public Object broadcast() {
        try {
            return b.broadcast(message).get();
        } catch (Exception ex) {
            logger.error("failed to broadcast message: " + message, ex);
        }
        return null;
    }

    public Object getMessage() {
        return message;
    }

    public Broadcaster getBroadcaster() {
        return b;
    }

    public Object getResponseMessage() {
        return callerMessage;
    }
}
