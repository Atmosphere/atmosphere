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
package org.atmosphere.samples.chat;

import org.atmosphere.config.service.MeteorService;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.Meteor;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Date;

import static org.atmosphere.cpr.AtmosphereResource.TRANSPORT.LONG_POLLING;

/**
 * Simple Servlet that implement the logic to build a Chat application using
 * a {@link Meteor} to suspend and broadcast chat message.  The Meteor is annotated using the {@link MeteorService}
 * but can also be defined in web.xml using
 * <blockquote>
 * <init-param>
 *    <param-name>org.atmosphere.servlet</param-name>
 *    <param-value>org.atmosphere.samples.chat.MeteorChat</param-value>
 * </init-param>
 * <p/>
 * </blockquote>
 *
 * @author Jeanfrancois Arcand
 */
@MeteorService(path = "/*", interceptors = {AtmosphereResourceLifecycleInterceptor.class})
public class MeteorChat extends HttpServlet {

    /**
     * Create a {@link Meteor} and use it to suspend the response.
     *
     * @param req An {@link HttpServletRequest}
     * @param res An {@link HttpServletResponse}
     */
    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        // Set the logger level to TRACE to see what's happening.
        Meteor m = Meteor.build(req).addListener(new AtmosphereResourceEventListenerAdapter());

        m.resumeOnBroadcast(m.transport() == LONG_POLLING ? true : false).suspend(-1);
    }

    /**
     * Re-use the {@link Meteor} created on the first GET for broadcasting message.
     *
     * @param req An {@link HttpServletRequest}
     * @param res An {@link HttpServletResponse}
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String body = req.getReader().readLine().trim();
        // Simple JSON -- Use Jackson for more complex structure
        // Message looks like { "author" : "foo", "message" : "bar" }
        String author = body.substring(body.indexOf(":") + 2, body.indexOf(",") - 1);
        String message = body.substring(body.lastIndexOf(":") + 2, body.length() - 2);
        BroadcasterFactory.getDefault().lookup(DefaultBroadcaster.class, "/*").broadcast(new Data(author, message).toString());
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
