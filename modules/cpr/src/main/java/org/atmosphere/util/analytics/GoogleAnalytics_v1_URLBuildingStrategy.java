/*
 * Copyright 2008-2022 Async-IO.org
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
package org.atmosphere.util.analytics;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Random;

/**
 * Fork of https://code.google.com/p/jgoogleanalytics/
 * <p/>
 * URL building logic for the earlier versions of google analytics (urchin.js)
 *
 * @author : Siddique Hameed
 * @version : 0.1
 */

public class GoogleAnalytics_v1_URLBuildingStrategy implements URLBuildingStrategy {
    private final FocusPoint appFocusPoint;
    private final String googleAnalyticsTrackingCode;
    private String refererURL = "http://async-io.org";

    private static final String TRACKING_URL_Prefix = "http://www.google-analytics.com/__utm.gif";

    private static final Random random = new Random();
    private static String hostName = "localhost";

    static {
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            //ignore this
        }
    }


    public GoogleAnalytics_v1_URLBuildingStrategy(String appName, String googleAnalyticsTrackingCode) {
        this.googleAnalyticsTrackingCode = googleAnalyticsTrackingCode;
        this.appFocusPoint = new FocusPoint(appName);
    }

    public GoogleAnalytics_v1_URLBuildingStrategy(String appName, String appVersion, String googleAnalyticsTrackingCode) {
        this.googleAnalyticsTrackingCode = googleAnalyticsTrackingCode;
        this.appFocusPoint = new FocusPoint(appVersion, new FocusPoint(appName));
    }


    public String buildURL(FocusPoint focusPoint) {

        int cookie = random.nextInt();
        int randomValue = random.nextInt(2147483647) - 1;
        long now = new Date().getTime();

        focusPoint.setParentTrackPoint(appFocusPoint);
        return TRACKING_URL_Prefix + "?utmwv=1" + //Urchin/Analytics version
                "&utmn=" + random.nextInt() +
                "&utmcs=UTF-8" + //document encoding
                "&utmsr=1440x900" + //screen resolution
                "&utmsc=32-bit" + //color depth
                "&utmul=en-us" + //user language
                "&utmje=1" + //java enabled
                "&utmfl=9.0%20%20r28" + //flash
                "&utmcr=1" + //carriage return
                "&utmdt=" + focusPoint.getContentTitle() + //The optimum keyword density //document title
                "&utmhn=" + hostName +//document hostname
                "&utmr=" + refererURL + //referer URL
                "&utmp=" + focusPoint.getContentURI() +//document page URL
                "&utmac=" + googleAnalyticsTrackingCode +//Google Analytics account
                "&utmcc=__utma%3D'" + cookie + "." + randomValue + "." + now + "." + now + "." + now + ".2%3B%2B__utmb%3D" + cookie + "%3B%2B__utmc%3D" + cookie + "%3B%2B__utmz%3D" + cookie + "." + now + ".2.2.utmccn%3D(direct)%7Cutmcsr%3D(direct)%7Cutmcmd%3D(none)%3B%2B__utmv%3D" + cookie;
    }

    public void setRefererURL(String refererURL) {
        this.refererURL = refererURL;
    }
}
