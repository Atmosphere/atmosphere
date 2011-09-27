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
package org.atmosphere.samples.tictactoe;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Simple handler that listens for GET and POST requests, and responds appropriately.
 * Uses long polling, with a GET that listens for new data, and a POST that triggers
 * broadcasts to all clients, resuming the GETs and returning that data.
 * <p/>
 * Actual game logic is held in a separate class - {@link TTTGame}.
 *
 * @author driscoll
 */
public class TTTHandler implements AtmosphereHandler<HttpServletRequest, HttpServletResponse> {

    private static TTTGame game = new TTTGame();

    /**
     * On GET, suspend the conneciton. On POST, update game logic, send
     * broadcast, and resume the connection.
     *
     * @param event
     * @throws IOException
     */
    public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {

        HttpServletRequest req = event.getRequest();
        HttpServletResponse res = event.getResponse();

        res.setContentType("text/html");
        res.addHeader("Cache-Control", "private");
        res.addHeader("Pragma", "no-cache");
        if (req.getMethod().equalsIgnoreCase("GET")) {
            event.suspend();
        } else if (req.getMethod().equalsIgnoreCase("POST")) {

            // There are better ways to do this, but it's the simplest way to
            // ensure that there is data consistency
            synchronized (game) {
                int cell = -1;
                String cellStr = req.getParameter("cell");
                PrintWriter writer = res.getWriter();
                writer.println("cell is '" + cellStr + "'");
                if (cellStr == null) {
                    writer.println("error - cell not set");
                    return;
                }
                try {
                    cell = Integer.parseInt(cellStr);
                } catch (NumberFormatException nfe) {
                    writer.println("error - cellStr not an int: " + cellStr);
                    return;
                }
                if (!game.turn(cell)) {
                    writer.println("warning - invalid move");
                }
                writer.println(game.getJSON());

                Broadcaster bc = event.getBroadcaster();

                String response = game.getJSON();

                // broadcast the updated game state
                bc.broadcast(response);

                writer.flush();

                if (game.win() != -1) {
                    game = new TTTGame();
                }
            }
        }
    }

    /**
     * Resume the underlying response on the first Broadcast
     *
     * @param event
     * @return event
     * @throws IOException
     */
    public void onStateChange(
            AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {


        // Client closed the connection.
        if (event.isCancelled()) {
            return;
        }
        if (!event.isResumedOnTimeout()) {
            String response = (String) event.getMessage();
            response = "<script type='text/javascript'>parent.chImg(" + response + ")</script>\n";
            PrintWriter writer = event.getResource().getResponse().getWriter();
            writer.write(response);
            writer.flush();
            event.getResource().resume();
        }
    }

    public void destroy() {
    }
}


