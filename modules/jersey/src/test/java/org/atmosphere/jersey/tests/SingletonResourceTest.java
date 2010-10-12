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

package org.atmosphere.jersey.tests;

import org.testng.annotations.Test;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 *
 * @author paulsandoz
 */
public class SingletonResourceTest extends BaseGrizzyTest {

    String getUrlTarget(int port) {
        return "http://127.0.0.1:" + port + "/singleton";
    }

    @Test(timeOut = 20000)
    public void testSingletonSuspendTimeout() {
        System.out.println("Running testSingletonSuspendTimeout");
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            long t1 = System.currentTimeMillis();
            Response r = c.prepareGet(urlTarget).execute().get(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, "singleton");
            long current = System.currentTimeMillis() - t1;
            assertTrue(current > 5000 && current < 10000);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }

}
