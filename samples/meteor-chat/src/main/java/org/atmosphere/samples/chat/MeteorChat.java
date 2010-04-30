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

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Meteor;
import org.atmosphere.util.XSSHtmlFilter;
import org.atmosphere.commons.jersey.JsonpFilter;
import org.atmosphere.commons.util.EventsLogger;

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
        Meteor m = (Meteor)req.getSession().getAttribute("meteor");
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
