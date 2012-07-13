package org.atmosphere.gwt.server.impl;

import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import com.google.gwt.user.server.rpc.SerializationPolicyProvider;
import com.google.gwt.user.server.rpc.impl.ServerSerializationStreamReader;
import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author p.havelaar
 */
public class GwtRpcDeserializer {
    
    private final static Logger logger = Logger.getLogger(GwtRpcDeserializer.class.getName());
    
    protected SerializationPolicyProvider cometSerializationPolicyProvider = new SerializationPolicyProvider() {
        @Override
        public SerializationPolicy getSerializationPolicy(String moduleBaseURL, String serializationPolicyStrongName) {
            return RPCUtil.createSimpleSerializationPolicy();
        }
    };
    
    public Serializable deserialize(String data) {
        try {
            ServerSerializationStreamReader reader = new ServerSerializationStreamReader(getClass().getClassLoader(), cometSerializationPolicyProvider);
            reader.prepareToRead(data);
            return (Serializable) reader.readObject();
        } catch (SerializationException ex) {
            logger.log(Level.SEVERE, "Failed to deserialize RPC data", ex);
            return null;
        }
    }

}
