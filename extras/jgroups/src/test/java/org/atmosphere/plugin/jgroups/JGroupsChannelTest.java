package org.atmosphere.plugin.jgroups;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import java.util.concurrent.Future;

import org.atmosphere.cpr.Broadcaster;
import org.jgroups.JChannel;
import org.jgroups.protocols.pbcast.FLUSH;
import org.testng.annotations.Test;

public class JGroupsChannelTest {

    @Test (enabled = false)
    public void broadcastsClusteredMessage() throws Exception {
        Broadcaster broadcaster = mock(Broadcaster.class);
        Future broadcastedMessage = mock(Future.class);
        
        when(broadcaster.getID()).thenReturn("/topic");
        when(broadcaster.broadcast("message")).thenReturn(broadcastedMessage);
        
        JChannel channel1 = new JChannel();
        JChannel channel2 = new JChannel();
        
        channel1.getProtocolStack().insertProtocolAtTop(new FLUSH());
        channel2.getProtocolStack().insertProtocolAtTop(new FLUSH());
        
        JGroupsChannel node1 = new JGroupsChannel(channel1, "cluster");
        JGroupsChannel node2 = new JGroupsChannel(channel2, "cluster");
        
        node1.addBroadcaster(broadcaster);
        node2.addBroadcaster(broadcaster);
        
        node1.init();
        node2.init();
        
        node2.send("/topic", "message");
        
        channel2.startFlush(false);
        
        assertEquals(channel1.getReceivedMessages(), 1);
        assertEquals(channel2.getReceivedMessages(), 1);
        verify(broadcaster, times(1)).broadcast("message");
    }

}
