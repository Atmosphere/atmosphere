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

package org.atmosphere.annotation;

import org.atmosphere.cpr.AtmosphereResourceEventListener;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Suspend the underlying response. Once suspended, a response might be allowed
 * to consume {@link Broadcast} events, depending on the scope ([@link Suspend#SCOPE}).
 * By default, a suspended response is suspended able to consume
 * any broadcasted events executed inside the same application (SCOPE.APPLICATION).
 * The period can also be per suspended response (SCOPE.REQUEST) or available to other
 * application (SCOPE.VM).
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Suspend {

    /**
     * How long a response stay suspended in {@link #timeUnit}, default is -1
     *
     * @return
     */
    int period() default -1;

    /**
     * @return
     */
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;

    enum SCOPE {
        REQUEST, APPLICATION, VM
    }

    /**
     * The Scope of the {@link org.atmosphere.cpr.Broadcaster} that will be created once the
     * response gets suspended. One final word on Broadcaster: by default,
     * a Broadcaster will broadcast using
     * all resources/classes on which the response has been suspended.
     * This behavior is configurable and you can configure it setting the appropriate scope<ul>
     * <li>REQUEST: broadcast events only to the suspended response associated with the current request.</li>
     * <li>APPLICATION: broadcast events to all suspended responses associated with the current application.</li>
     * <li>VM: broadcast events to all suspended responses created inside the current virtual machine.</li>
     * </ul>
     *
     * @return The Scope of the {@link org.atmosphere.cpr.Broadcaster} that will be created once the
     *         response gets suspended.
     */
    SCOPE scope() default SCOPE.APPLICATION;

    /**
     * By default, output some comments when suspending the connection.
     */
    boolean outputComments() default true;

    /**
     * Resume all suspended response on the first broadcast operation.
     */
    public boolean resumeOnBroadcast() default false;

    /**
     * Add {@link AtmosphereResourceEventListener} to the broadcast operation.
     */
    public Class<? extends AtmosphereResourceEventListener>[] listeners() default {};

    /**
     * Write the returned entity back to the calling connection. Default is false.
     * @return true if the entity needs to be written back to the calling connection.
     */
    public boolean writeEntity() default true;

    /**
     * If the @Produces annotation is missing, this value will be used instead.
     * @return the default content-type used if the @Produces annotation is missing.
     */
    public String contentType() default "";
}
