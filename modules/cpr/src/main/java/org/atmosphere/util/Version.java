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

package org.atmosphere.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class contains information about Atmosphere framework
 *
 * @author Charlie Hunt
 * @author Hubert Iwaniuk
 */
public class Version {

    private static final Pattern versionPattern = Pattern.compile("((\\d+)\\.(\\d+)\\.(\\d+)){1}(.+)?");
    private static final String dotedVersion;
    private static final int major;
    private static final int minor;
    private static final int micro;
    private static final String version;

    public static void main(String[] args) {
        System.out.println(Version.getDotedVersion());
    }

    /** Reads version from properties and parses it. */
    static {
        Properties prop = new Properties();
        InputStream s = null;
        try {
            s = Version.class.getResourceAsStream("version.properties");
            prop.load(s);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            e.printStackTrace();
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ex) {
                }
            }
        }
        version = prop.getProperty("atmosphere.version");
        Matcher matcher = versionPattern.matcher(version);
        if (matcher.matches()) {
            dotedVersion = matcher.group(1);
            major = Integer.parseInt(matcher.group(2));
            minor = Integer.parseInt(matcher.group(3));
            micro = Integer.parseInt(matcher.group(4));
        } else {
            dotedVersion = "no.version";
            major = -1;
            minor = -1;
            micro = -1;
        }
    }

    /**
     * Return the raw version derived frok the project's pom.xml
     *
     * @return
     */
    public static String getRawVersion() {
        return version;
    }

    /**
     * Return the dotted version of the curent release.
     *
     * @return like "2.0.1"
     */
    public static String getDotedVersion() {
        return dotedVersion;
    }


    /**
     * Get Atmosphere framework major version
     *
     * @return Atmosphere framework major version
     */
    public static int getMajorVersion() {
        return major;
    }

    /**
     * Get Atmosphere framework minor version
     *
     * @return Atmosphere framework minor version
     */
    public static int getMinorVersion() {
        return minor;
    }

    /**
     * Return the micro version
     */
    public static int getMicroVersion() {
        return micro;
    }

    /**
     * Checks if current Atmosphere framework version equals to one passed
     *
     * @param major Atmosphere framework major version
     * @param minor Atmosphere framework minor version
     * @return true, if versions are equal; false otherwise
     */
    public static boolean equalVersion(int major, int minor) {
        return minor == Version.minor && major == Version.major;
    }
}
