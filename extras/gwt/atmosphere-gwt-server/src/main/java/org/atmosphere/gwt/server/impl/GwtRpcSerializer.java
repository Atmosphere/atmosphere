package org.atmosphere.gwt.server.impl;

import com.google.gwt.rpc.server.ClientOracle;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import com.google.gwt.user.server.rpc.SerializationPolicyProvider;
import com.google.gwt.user.server.rpc.impl.ServerSerializationStreamReader;
import com.google.gwt.user.server.rpc.impl.ServerSerializationStreamWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

/**
 *
 * @author p.havelaar
 */
public class GwtRpcSerializer {
    
    private final static Logger logger = Logger.getLogger(GwtRpcSerializer.class.getName());
    
    private final SerializationPolicy serializationPolicy;
    private final ClientOracle clientOracle;

    public GwtRpcSerializer(HttpServletRequest request, ServletContext context) {
        try {
            clientOracle = RPCUtil.getClientOracle(request, context);
            serializationPolicy = clientOracle == null ? RPCUtil.createSimpleSerializationPolicy() : null;
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Failed to create serialization policy", ex);
            throw new IllegalStateException("Failed to create serialization policy", ex);
        }
    }
    
    public String serialize(Object message) {
        try {
            ServerSerializationStreamWriter streamWriter = new ServerSerializationStreamWriter(serializationPolicy);
            streamWriter.prepareToWrite();
            streamWriter.writeObject(message);
            return streamWriter.toString();
        } catch (SerializationException ex) {
            logger.log(Level.SEVERE, "Failed to serialize message", ex);
            return null;
        }
    }
    
}
