package org.atmosphere.jackson;

import java.io.IOException;
import java.util.Map;
import org.atmosphere.gwt.server.JSONDeserializer;
import org.atmosphere.gwt.server.SerializationException;

/**
 *
 * @author p.havelaar
 */
public class JacksonDeserializer implements JSONDeserializer {

    final JacksonSerializerProvider provider;

    public JacksonDeserializer(JacksonSerializerProvider provider) {
        this.provider = provider;
    }

    @Override
    public Object deserialize(String data) throws SerializationException{
        try {
            // TODO not the most neat implementation
            return provider.mapper.readValue(data, Map.class);
        } catch (IOException ex) {
            throw new SerializationException("Failed to deserialize data", ex);
        }
    }
}
