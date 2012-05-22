package org.atmosphere.gwt.client;

import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.SerializationStreamReader;
import com.google.gwt.user.client.rpc.SerializationStreamWriter;
import java.io.Serializable;

/**
 *
 * @author p.havelaar
 */
public final class AtmosphereProxyEvent_CustomFieldSerializer {

    public static void deserialize(SerializationStreamReader streamReader, AtmosphereProxyEvent instance) throws SerializationException {
        instance.setEventType((AtmosphereProxyEvent.EventType)streamReader.readObject());
        instance.setData((Serializable)streamReader.readObject());
    }

    public static void serialize(SerializationStreamWriter streamWriter, AtmosphereProxyEvent instance) throws SerializationException {
        streamWriter.writeObject(instance.getEventType());
        streamWriter.writeObject(instance.getData());
    }
    
}
