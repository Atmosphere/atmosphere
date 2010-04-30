/**
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER. *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved. *
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
package org.atmosphere.grizzly;

import com.sun.grizzly.http.deployer.DeployException;
import com.sun.grizzly.http.servlet.deployer.GrizzlyWebServerDeployer;
import com.sun.grizzly.http.servlet.deployer.WarDeployer;
import com.sun.grizzly.http.servlet.deployer.WarDeploymentConfiguration;
import com.sun.grizzly.http.servlet.deployer.conf.DeployerConfiguration;
import com.sun.grizzly.http.webxml.schema.WebApp;
import org.atmosphere.grizzly.conf.AtmosphereConfigurationParser;
import org.atmosphere.grizzly.conf.AtmosphereDeployerConfiguration;

import java.io.File;
import java.net.URLClassLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Sebastien Dionne
 */
public class AtmosphereDeployer extends GrizzlyWebServerDeployer {

    private static Logger logger = Logger.getLogger(AtmosphereDeployer.class.getName());

    private WarDeployer deployer = new AtmosphereWarDeployer();

    /**
     * @param args Command line parameters.
     */
    public static void main(String[] args) {
        new AtmosphereDeployer().launch(init(args));
    }

    public static AtmosphereDeployerConfiguration init(String args[]) {
        AtmosphereDeployerConfiguration cfg = AtmosphereConfigurationParser.parseOptions(args, AtmosphereDeployer.class.getCanonicalName());
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, cfg.toString());
        }
        return cfg;
    }

    /**
     * Deploy WAR file.
     *
     * @param location        Location of WAR file.
     * @param context         Context to deploy to.
     * @param serverLibLoader Server wide {@link ClassLoader}. Optional.
     * @param defaultWebApp   webdefault application, get's merged with application to deploy. Optional.
     * @throws DeployException Deployment failed.
     */
    public void deployWar(
            String location, String context, URLClassLoader serverLibLoader, WebApp defaultWebApp, final DeployerConfiguration conf) throws DeployException {
        String ctx = context;
        if (ctx == null) {
            ctx = getContext(location);
            int i = ctx.lastIndexOf('.');
            if (i > 0) {
                ctx = ctx.substring(0, i);
            }
        }
        WarDeploymentConfiguration config = new WarDeploymentConfiguration(ctx, serverLibLoader, defaultWebApp, conf);
        if (logger.isLoggable(Level.FINER)) {
            logger.log(Level.FINER, String.format("Configuration for deployment: %s.", config));
        }
        deployer.deploy(ws, new File(location).toURI(), config);
    }
}
