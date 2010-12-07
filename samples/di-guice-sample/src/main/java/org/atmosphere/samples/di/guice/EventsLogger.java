/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://jersey.dev.java.net/CDDL+GPL.html
 * or jersey/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at jersey/legal/LICENSE.txt.
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
package org.atmosphere.samples.di.guice;

import com.google.inject.Inject;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author Mathieu Carbou
 * @since 0.7
 */
public class EventsLogger implements AtmosphereResourceEventListener {

    @Inject
    Service service;

    public void onSuspend(final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null)
            throw new AssertionError();
        System.out.println("[" + Thread.currentThread().getName() + "] onSuspend: " + event.getResource().getRequest().getRemoteAddr() + event.getResource().getRequest().getRemotePort());
    }

    public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null)
            throw new AssertionError();
        System.out.println("[" + Thread.currentThread().getName() + "] onResume: " + event.getResource().getRequest().getRemoteAddr());
    }

    public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null)
            throw new AssertionError();
        System.out.println("[" + Thread.currentThread().getName() + "] onDisconnect: " + event.getResource().getRequest().getRemoteAddr() + event.getResource().getRequest().getRemotePort());
    }

    public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null)
            throw new AssertionError();
        System.out.println("[" + Thread.currentThread().getName() + "] onBroadcast: " + event.getMessage().toString().replace("\n", "\\n"));
    }

    public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null)
            throw new AssertionError();
        event.throwable().printStackTrace(System.err);
    }
}
