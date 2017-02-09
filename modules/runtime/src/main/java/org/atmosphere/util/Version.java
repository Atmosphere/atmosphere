/*
 * Copyright 2017 Async-IO.org
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
