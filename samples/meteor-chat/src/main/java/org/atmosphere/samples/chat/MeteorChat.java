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

import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Meteor;
import org.atmosphere.util.XSSHtmlFilter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * Simple Servlet that implement the logic to build a Chat application using
 * a {@link Meteor} to suspend and broadcast chat message.
 *
 * @author Jeanfrancois Arcand
 * @autor TAKAI Naoto (Orginial author for the Comet based Chat).
 */
public class MeteorChat extends HttpServlet {

    private final static long serialVersionUID = -2919167206889576860L;

    /**
     * List of {@link BroadcastFilter}
     */
    private final List<BroadcastFilter> list;

    public MeteorChat() {
        list = new LinkedList<BroadcastFilter>();
        list.add(new XSSHtmlFilter());
        list.add(new JsonpFilter());
    }

    /**
     * Create a {@link Meteor} and use it to suspend the response.
     *
     * @param req An {@link HttpServletRequest}
     * @param res An {@link HttpServletResponse}
     */
    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Meteor m = Meteor.build(req, list, null);

        // Log all events on the concole.
        m.addListener(new EventsLogger());

        req.getSession().setAttribute("meteor", m);

        res.setContentType("text/html;charset=ISO-8859-1");

        m.suspend(-1);
        m.broadcast(req.getServerName() + "__has suspended a connection from " + req.getRemoteAddr());
    }

    /**
     * Re-use the {@link Meteor} created onthe first GET for broadcasting message.
     *
     * @param req An {@link HttpServletRequest}
     * @param res An {@link HttpServletResponse}
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Meteor m = (Meteor) req.getSession().getAttribute("meteor");
        res.setCharacterEncoding("UTF-8");
        String action = req.getParameterValues("action")[0];
        String name = req.getParameterValues("name")[0];

        if ("login".equals(action)) {
            req.getSession().setAttribute("name", name);
            m.broadcast("System Message from " + req.getServerName() + "__" + name + " has joined.");
            res.getWriter().write("success");
            res.getWriter().flush();
        } else if ("post".equals(action)) {
            String message = req.getParameterValues("message")[0];
            m.broadcast(name + "__" + message);
            res.getWriter().write("success");
            res.getWriter().flush();
        } else {
            res.setStatus(422);

            res.getWriter().write("success");
            res.getWriter().flush();
        }
    }

}
