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
package org.atmosphere.samples.twitter;

import org.atmosphere.samples.twitter.UsersState.UserStateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;

public class UserResource {

    private static final Logger logger = LoggerFactory.getLogger(UserResource.class);

    private final UsersState us;

    private String user;

    private UserStateData usd;

    public UserResource(UsersState us, String name, UserStateData usd) {
        this.us = us;
        this.user = name;
        this.usd = usd;
    }

    @GET
    public String get() {
        return "";
    }

    @DELETE
    public void delete() {
        us.remove(user);
    }

    @Path("messages")
    @POST
    public void post(String message) {
        logger.info("MESSAGE: {}", message);
    }

    @Path("follows")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public void follow(@FormParam("follower") String follower) {
        UserStateData followerState = us.get(follower);
        // User does not exist
        if (followerState == null) {
            throw new WebApplicationException(404);
        }

        followerState.bc.addAtmosphereResource(us.get(user).bc.getUserAtmosphereEvent().getResource());
        followerState.bc.broadcast(user + " is now follow you ",
                followerState.bc.getUserAtmosphereEvent().getResource());

        logger.info("{} is following {}", user, follower);
    }
}