package org.atmosphere.gwt.js;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.user.client.rpc.SerializationException;
import org.atmosphere.gwt.client.JSONObjectSerializer;

/**
 *
 * @author p.havelaar
 */
public class NativeJSONObjectSerializer implements JSONObjectSerializer {

    @Override
    public Object deserialize(String message) throws SerializationException {
        return decodeJSON(message);
    }

    @Override
    public String serialize(Object message) throws SerializationException {
        return encodeJSON((JavaScriptObject) message);
    }

            
    private native static String encodeJSON(JavaScriptObject obj) /*-{
        return $wnd.atmosphere_JSON.encode(obj);
    }-*/;

    private native static JavaScriptObject decodeJSON(String json) /*-{
        return $wnd.atmosphere_JSON.decode(json);
    }-*/;

}
