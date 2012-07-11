package org.atmosphere.jackson;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.atmosphere.gwt.server.JSONSerializer;
import org.atmosphere.gwt.server.SerializationException;

/**
 *
 * @author p.havelaar
 */
public class JacksonSerializer implements JSONSerializer {

    final JacksonSerializerProvider provider;

    public JacksonSerializer(JacksonSerializerProvider provider) {
        this.provider = provider;
    }
    

    @Override
    public String serialize(Object data) throws SerializationException {
        try {
            return provider.mapper.writeValueAsString(data);
        } catch (IOException ex) {
            throw new SerializationException("Failed to serialize data", ex);
        }
    }
    
}
