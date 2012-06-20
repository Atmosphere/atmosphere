package org.atmosphere.gwt.client.impl;

import com.google.gwt.user.client.rpc.SerializationException;
import org.atmosphere.gwt.client.JSONObjectSerializer;

/**
 * Please replace this stub with the an appropriate implementation
 * see atmosphere-gwt-extra
 * 
 * @author p.havelaar
 */
public class JSONObjectSerializerStub implements JSONObjectSerializer {

    @Override
    public Object deserialize(String message) throws SerializationException {
        return message;
    }

    @Override
    public String serialize(Object message) throws SerializationException {
        return message.toString();
    }
    
}
