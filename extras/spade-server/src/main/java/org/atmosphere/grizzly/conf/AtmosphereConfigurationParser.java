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
package org.atmosphere.grizzly.conf;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link org.atmosphere.grizzly.AtmosphereDeployer} configuration parser.
 *
 * @author Sebastien Dionne
 */
public class AtmosphereConfigurationParser {

    private static Logger logger = Logger.getLogger(AtmosphereConfigurationParser.class.getName());

    /**
     * Parse command line parameters.
     *
     * @param args          Command line parameters to parse.
     * @param canonicalName Class canonical name.
     * @return Parsed configuration.
     */
    public static AtmosphereDeployerConfiguration parseOptions(String[] args, final String canonicalName) {
        AtmosphereDeployerConfiguration conf = new AtmosphereDeployerConfiguration();
        if (args.length == 0) {
            printHelpAndExit(canonicalName);
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("-h".equals(arg) || "--help".equals(arg)) {
                printHelpAndExit(canonicalName);
            } else if ("--longhelp".equals(arg)) {
                printLongHelpAndExit(canonicalName);
            } else if ("-a".equals(arg)) {
                i++;
                if (i < args.length) {
                    conf.locations = args[i];
                }
            } else if (arg.startsWith("--application=")) {
                conf.locations = arg.substring("--application=".length(), arg.length());
            } else if ("-p".equals(arg)) {
                i++;
                if (i < args.length) {
                    conf.port = Integer.parseInt(args[i]);
                }
            } else if (arg.startsWith("--port=")) {
                String num = arg.substring("--port=".length(), arg.length());
                conf.port = Integer.parseInt(num);
            } else if ("-c".equals(arg)) {
                i++;
                if (i < args.length) {
                    conf.forcedContext = args[i];
                }
            } else if (arg.startsWith("--context=")) {
                conf.forcedContext = arg.substring("--context=".length(), arg.length());
            } else if (arg.startsWith("--dontstart=")) {
                conf.waitToStart = Boolean
                        .parseBoolean(arg.substring("--dontstart=".length(), arg.length()));
            } else if (arg.startsWith("--libraryPath=")) {
                conf.libraryPath = arg.substring("--libraryPath=".length(), arg.length());
            } else if (arg.startsWith("--webdefault=")) {
                conf.webdefault = arg.substring("--webdefault=".length(), arg.length());
            } else if (arg.startsWith("--autodeploy=")) {
                conf.webdefault = arg.substring("--autodeploy=".length(), arg.length());
            } else if (arg.startsWith("--cometEnabled=")) {
                conf.cometEnabled = Boolean
                        .parseBoolean(arg.substring("--cometEnabled=".length(), arg.length()));
            } else if (arg.startsWith("--forceWar")) {
                conf.forceWarDeployment = Boolean
                        .parseBoolean(arg.substring("--forceWar=".length(), arg.length()));
            } else if (arg.startsWith("--ajpEnabled")) {
                conf.ajpEnabled = Boolean
                        .parseBoolean(arg.substring("--ajpEnabled=".length(), arg.length()));
            }
        }

        if (conf.locations == null) {
            logger.log(Level.SEVERE, "Illegal War|Jar file or folder location.");
            printHelpAndExit(canonicalName);
        }

        return conf;
    }

    private static void printHelpAndExit(final String canonicalName) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("\nUsage: ").append(canonicalName)
                .append("\n  --application=[path]*       Application(s) path(s).\n")
                .append("  --port=[port]               Runs Servlet on the specified port.\n")
                .append("  --context=[context]         Force the context for a servlet.\n")
                .append("  --dontstart=[true/false]    Won't start the server.\n")
                .append("  --libraryPath=[path]        Add a libraries folder to the classpath.\n")
                .append("  --autodeploy=[path]         AutoDeploy to each applications\n")
                .append("  --webdefault=[path]         webdefault to be used by all applications, can be file or dir with multipe web.xmls\n")
                .append("  --cometEnabled              Starts the AsyncFilter for Comet\n")
                .append("  --forceWar                  Force war's deployment over a expanded folder.\n")
                .append("  --ajpEnabled                Enable mod_jk.\n")
                .append("  --help                      Show this help message.\n")
                .append("  --longhelp                  Show detailled help message.\n\n")
                .append("  * are mandatory");
        logger.log(Level.SEVERE, sb.toString());
        System.exit(1);
    }

    private static void printLongHelpAndExit(final String canonicalName) {
        System.err.println();
        System.err.println("Usage: " + canonicalName);
        System.err.println();
        System.err.println("  -a, --application=[path]*   Application(s) path(s).");
        System.err.println();
        System.err.println("                              Application(s) deployed can be :");
        System.err.println(
                "                              Servlet(s), war(s) and expanded war folder(s).");
        System.err.println("                              To deploy multiple applications");
        System.err.println("                              use File.pathSeparator");
        System.err.println();
        System.err.println(
                "                              Example : -a /app.war:/servlet/web.xml:/warfolder/");
        System.err.println();
        System.err.println("  -p, --port=[port]           Runs Servlet on the specified port.");
        System.err.println("                              Default: 8080");
        System.err.println();
        System.err.println("  -c, --context=[context]     Force the context for a servlet.");
        System.err.println("                              Only valid for servlet deployed using");
        System.err.println("                              -a [path]/[filename].xml");
        System.err.println();
        System.err.println("  --dontstart=[true/false]    Won't start the server.");
        System.err.println("                              You will need to call the start method.");
        System.err.println("                              Useful for Unit testing.");
        System.err.println("                              Default : false");
        System.err.println();
        System.err
                .println("  --libraryPath=[path]        Add a libraries folder to the classpath.");
        System.err.println("                              You can append multiple folders using");
        System.err.println("                              File.pathSeparator");
        System.err.println();
        System.err
                .println("                              Example : --libraryPath=/libs:/common_libs");
        System.err.println();
        System.err.println("  --autodeploy=[path]         AutoDeploy to each applications.");
        System.err.println("                              You could add JSP support.");
        System.err.println("                              Just add a web.xml that contains Jasper");
        System.err.println();
        System.err.println("                              Example : --autodeploy=/autodeploy");
        System.err.println();
        System.err.println("  --webdefault=[path]         webdefault to be used by all applications, can be file or dir with multipe web.xmls.");
        System.err.println("                              If you want to add only one webdefault point it to web.xml file,");
        System.err.println("                              If you want multiple files to be included put them in one dir and provide this location here.");
        System.err.println();
        System.err.println("                              Example : --webdefault=webdefault.xml");
        System.err.println();
        System.err.println("  --cometEnabled=[true/false] Starts the AsyncFilter for Comet.");
        System.err.println(
                "                              You need to active this for comet applications.");
        System.err.println("                              Default : true");
        System.err.println();
        System.err.println(
                "  --forceWar=[true/false]     Force war's deployment over a expanded folder.");
        System.err
                .println("                              Will deploy the war instead of the folder.");
        System.err.println("                              Default : false");
        System.err.println();
        System.err.println("  --ajpEnabled=[true/false]   Enable mod_jk.");
        System.err.println("                              Default : false");
        System.err.println();
        System.err.println("  Default values will be applied if invalid values are passed.");
        System.err.println();
        System.err.println("  * are mandatory");
        System.exit(1);
    }
}
