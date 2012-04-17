/*
 * Copyright 2012 Jean-Francois Arcand
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
package org.atmosphere.plugin.jgroups;

import org.jgroups.JChannel;

/**
 * Creates a default, singleton JGroupsChannel object
 *
 * @author westraj
 */
public class DefaultJGroupsChannelFactory {
    private static JGroupsChannel jc;

    public static String DEFAULT_JGROUPS_XML = "org/atmosphere/plugin/jgroups/JGroupsFilter.xml";
    public static String DEFAULT_CHANNEL_NAME = "JGroupsChannel";

    private DefaultJGroupsChannelFactory() {
    }

    public static synchronized JGroupsChannel getDefaultJGroupsChannel() {
        return getDefaultJGroupsChannel(DEFAULT_JGROUPS_XML, DEFAULT_CHANNEL_NAME);
    }

    public static synchronized JGroupsChannel getDefaultJGroupsChannel(String jGroupsFilterLocation) {
        return getDefaultJGroupsChannel(jGroupsFilterLocation, DEFAULT_CHANNEL_NAME);
    }

    public static synchronized JGroupsChannel getDefaultJGroupsChannel(String jGroupsFilterLocation, String channelName) {
        if (jc == null) {
            try {
                JChannel channel = new JChannel(jGroupsFilterLocation);
                jc = new JGroupsChannel(channel, channelName);

                jc.init();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create JGroupsChannel", e);
            }
        }

        return jc;
    }
}
