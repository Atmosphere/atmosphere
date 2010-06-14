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
package app;

import org.apache.log4j.Logger;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.actions.DispatchAction;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.Meteor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedList;
import java.util.List;

public class SimpleAction extends DispatchAction {
    public static final Logger logger = Logger.getLogger(SimpleAction.class);
    private final List<BroadcastFilter> list;
    private final Broadcaster b = new DefaultBroadcaster("Struts");

    public SimpleAction() {
        list = new LinkedList<BroadcastFilter>();
        //list.add(new XSSHtmlFilter());
        //list.add(new JsonpFilter());
    }

    public ActionForward unspecified(ActionMapping mapping, ActionForm _form, HttpServletRequest req, HttpServletResponse res) throws Exception {
        logger.error("IN ACTION");
        return mapping.findForward("success");
    }

    public ActionForward echo(ActionMapping mapping, ActionForm _form, HttpServletRequest req, HttpServletResponse res) throws Exception {
        logger.info("BEGIN SimpleAction.echo()");
        String value = req.getParameter("value");
        // Do something with value
        res.getWriter().print("{message: 'Server says: " + value + "'}");
        return null;
    }

    public ActionForward openCometChannel(ActionMapping mapping, ActionForm _form, HttpServletRequest req, HttpServletResponse res) throws Exception {
        logger.info("BEGIN SimpleAction.openCometChannel()");
        Meteor m = Meteor.build(req, list, null);
        m.setBroadcaster(b);
        req.getSession().setAttribute("meteor", m);
        m.suspend(-1);
        m.broadcast(req.getServerName()
                + "__has suspended a connection from " + req.getRemoteAddr());
        return null;
    }

    public ActionForward sendCometMsg(ActionMapping mapping, ActionForm _form, HttpServletRequest req, HttpServletResponse res) throws Exception {
        logger.info("BEGIN SimpleAction.sendCometMsg()");
        Meteor m = (Meteor) req.getSession().getAttribute("meteor");
        logger.info("meteor: " + m);
        res.setCharacterEncoding("UTF-8");
        String value = req.getParameter("value");
        logger.debug("value: " + value);

        m.broadcast("<script>parent.cometMsg('Broadcast: " + value + "');</script>");
        res.getWriter().write("{message:'success'}");
        res.getWriter().flush();
        return null;
    }
}
