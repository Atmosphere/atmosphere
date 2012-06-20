package org.atmosphere.gwt.client.extra;

import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.rpc.SerializationException;
import com.kfuntak.gwt.json.serialization.client.Serializer;
import org.atmosphere.gwt.client.JSONObjectSerializer;

/**
 *
 * @author p.havelaar
 */
public class JSONObjectSerializerGWTPro implements JSONObjectSerializer {
    
    private Serializer serializer = GWT.create(Serializer.class);

    @Override
    public Object deserialize(String message) throws SerializationException {
        return deserialize(JSONParser.parseLenient(message));
    }

    @Override
    public String serialize(Object message) throws SerializationException {
        return serializer.serialize(message);
    }
    
    public Object deserialize(JSONValue jsonValue) {
        JSONObject obj = jsonValue.isObject();
        if (obj != null) {
            if (obj.containsKey("class") && obj.get("class").isString() != null) {
                return serializer.deSerialize(jsonValue, obj.get("class").isString().stringValue());
            }
        }
        throw new IllegalArgumentException("Json string must contain \"class\" key.");
    }

}
