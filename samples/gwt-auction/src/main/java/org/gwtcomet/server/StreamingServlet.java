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
package org.gwtcomet.server;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcastFilter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.logging.Level;
import java.util.logging.Logger;

public class StreamingServlet extends RemoteServiceServlet {

    private final static byte[] JUNK = ("<!-- Comet is a programming technique that enables web " +
            "servers to send data to the client without having any need " +
            "for the client to request it. -->\n").getBytes();

    private int counter = 0;

    public StreamingServlet() {
    }

    private void writeToStream(OutputStream out, String contents) throws IOException {
        out.write(contents.getBytes());
        out.flush();
    }

    private void sendKeepAlive(OutputStream out) throws IOException {
        writeToStream(out, writeCallback(new StringBuffer(), "keepAliveInternal",
                Integer.toString(counter++)).toString());
    }

    private StringBuffer writeCallback(StringBuffer stream,
            String topicName, String data) throws UnsupportedEncodingException {
        stream.append(" <script type='text/javascript'>\n");
        stream.append("\twindow.parent.callback('" + topicName + 
                "',unescape('" + URLEncoder.encode(data, "iso-8859-1")
                .replaceAll("\\x2B", "%20") + "'));\n");
        stream.append("</script>\n");

        return stream;
    }


    @Override
    protected void doGet(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/html;charset=ISO-8859-1");

        AtmosphereResource e =
                (AtmosphereResource) request.getAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE);

        if (!e.getBroadcaster().getID().equals("GWT")){
            synchronized(e.getBroadcaster()){
                e.getBroadcaster().setID("GWT");
                e.getBroadcaster().getBroadcasterConfig().addFilter(new GWTBroadcasterFilter());
            }
        }

        sendKeepAlive(response.getOutputStream());
        e.suspend();
    }

    private final class GWTBroadcasterFilter implements BroadcastFilter {

        public BroadcastAction filter(Object originalMsg, Object message) {
            try {
                StreamingServiceBusiness.Event event = (StreamingServiceBusiness.Event) message;
                StringBuffer stream = new StringBuffer();
                stream.append("<script type='text/javascript'>\n");
                stream.append("\twindow.parent.callback('" + event.queueName +
                        "',unescape('" + URLEncoder.encode(event.message, "iso-8859-1")
                        .replaceAll("\\x2B", "%20") + "'));\n");
                stream.append("</script>\n");
                return new BroadcastAction(stream);
            } catch (UnsupportedEncodingException ex) {
                Logger.getLogger(StreamingServlet.class.getName()).log(Level.SEVERE, null, ex);
            }
            return new BroadcastAction(message);
        }
    }
}
