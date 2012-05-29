package org.atmosphere.gwt.client.extra;

import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONValue;
import com.kfuntak.gwt.json.serialization.client.Serializer;

/**
 *
 * @author p.havelaar
 */
public class JsonSerializerUtil {

    
    public static Object deserialize(Serializer serializer, String jsonString) {
        return deserialize(serializer, JSONParser.parseLenient(jsonString));
    }

    public static Object deserialize(Serializer serializer, JSONValue jsonValue) {
        JSONObject obj = jsonValue.isObject();
        if (obj != null) {
            if (obj.containsKey("class") && obj.get("class").isString() != null) {
                return serializer.deSerialize(jsonValue, obj.get("class").isString().stringValue());
            }
        }

        throw new IllegalArgumentException("Json string must contain \"class\" key.");
    }

}
