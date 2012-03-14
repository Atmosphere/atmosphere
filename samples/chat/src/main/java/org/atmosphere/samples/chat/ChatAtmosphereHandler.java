/*
 * Copyright 2012 Jeanfrancois Arcand
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
 *
 */
package org.atmosphere.samples.chat;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;

import java.io.IOException;
import java.util.Date;

/**
 * Simple AtmosphereHandler that implement the logic to build a Chat application.
 *
 * @author Jeanfrancois Arcand
 */
public class ChatAtmosphereHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource r) throws IOException {

        AtmosphereRequest req = r.getRequest();

        // First, tell Atmosphere to allow bi-directional communication by suspending.
        if (req.getMethod().equalsIgnoreCase("GET")) {
            // We are using HTTP long-polling with an invite timeout
            r.suspend();
        // Second, broadcast message to all connected users.
        } else if (req.getMethod().equalsIgnoreCase("POST")) {
            r.getBroadcaster().broadcast(req.getReader().readLine().trim());
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        AtmosphereResource r = event.getResource();
        AtmosphereResponse res = r.getResponse();

        if (event.isSuspended()) {
            String body = event.getMessage().toString();

            // Simple JSON -- Use Jackson for more complex structure
            // Message looks like { "author" : "foo", "message" : "bar" }
            String author = body.substring(body.indexOf(":") + 2, body.indexOf(",") - 1);
            String message = body.substring(body.lastIndexOf(":") + 2, body.length() - 2);

            res.getWriter().write(new Data(author, message).toString());
            switch (r.transport()) {
                case JSONP:
                case LONG_POLLING:
                    event.getResource().resume();
                    break;
                case WEBSOCKET :
                case STREAMING:
                    res.getWriter().flush();
                    break;
            }
        } else if (!event.isResuming()){
            event.getResource().getBroadcaster().broadcast(new Data("Someone", "say bye bye!").toString());
        }
    }

    @Override
    public void destroy() {
    }

    private final static class Data {

        private final String text;
        private final String author;

        public Data(String author, String text) {
            this.author = author;
            this.text = text;
        }

        public String toString() {
            return "{ \"text\" : \"" + text + "\", \"author\" : \"" + author + "\" , \"time\" : " + new Date().getTime() + "}";
        }
    }
}
