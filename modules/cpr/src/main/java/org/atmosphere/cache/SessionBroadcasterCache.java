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
package org.atmosphere.cache;

import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple {@link org.atmosphere.cpr.BroadcasterCache} that use an {@link javax.servlet.http.HttpSession} to cache
 * lost message.
 *
 * @author Jeanfrancois Arcand
 */
public class SessionBroadcasterCache extends AbstractBroadcasterCache {

    private static final Logger logger = LoggerFactory.getLogger(SessionBroadcasterCache.class);
    private static final String ERROR_MESSAGE = "Session was null. The request has been recycled by the underlying container";

    public SessionBroadcasterCache() {
    }

    @Override
    public CacheMessage addToCache(String broadcasterId, AtmosphereResource r, BroadcastMessage message) {
        long now = System.nanoTime();
        CacheMessage cacheMessage = put(message, now);

        if (r == null) return cacheMessage;

        try {
            HttpSession session = r.session();
            if (session == null) {
                logger.error(ERROR_MESSAGE);
                return cacheMessage;
            }

            session.setAttribute(broadcasterId, String.valueOf(now));
        } catch (IllegalStateException ex) {
            logger.trace("",ex);
            logger.warn("The Session has been invalidated. Message will be loat.");
        }
        return cacheMessage;
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, AtmosphereResource r) {
        if (r == null) {
            throw new IllegalArgumentException("AtmosphereResource can't be null");
        }

        List<Object> result = new ArrayList<Object>();
        try {
            HttpSession session = r.session();
            if (session == null) {
                logger.error(ERROR_MESSAGE);
                return result;
            }

            String cacheHeaderTimeStr = (String)session.getAttribute(broadcasterId);
            if (cacheHeaderTimeStr == null) return result;
            Long cacheHeaderTime = Long.valueOf(cacheHeaderTimeStr);
            if (cacheHeaderTime == null) return result;

            return get(cacheHeaderTime);
        } catch (IllegalStateException ex) {
            logger.trace("",ex);
            logger.warn("The Session has been invalidated. Unable to retrieve cached messages");
            return Collections.emptyList();
        }
    }
}
