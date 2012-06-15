/*
* Copyright 2012 Jeanfrancois Arcand
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy of
* the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations under
* the License.
*/
/*
 * Copyright 2009 Richard Zschech.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.atmosphere.gwt.client;

import org.atmosphere.gwt.shared.SerialMode;
import com.google.gwt.core.client.GWT;
import com.google.gwt.rpc.client.impl.ClientWriterFactory;
import com.google.gwt.rpc.client.impl.CommandToStringWriter;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.SerializationStreamReader;
import com.google.gwt.user.client.rpc.SerializationStreamWriter;
import com.google.gwt.user.client.rpc.impl.ClientSerializationStreamReader;
import com.google.gwt.user.client.rpc.impl.ClientSerializationStreamWriter;
import com.google.gwt.user.client.rpc.impl.Serializer;
import org.atmosphere.gwt.client.extra.JsonSerializerUtil;

/**
 * The base class for comet serializers. To instantiate this class follow this example:
 * <p/>
 * <code>
 *
 * @author Richard Zschech
 * @SerialTypes({ MyType1.class, MyType2.class })
 * public static abstract class MyCometSerializer extends CometSerializer {}
 * <p/>
 * CometSerializer serializer = GWT.create(MyCometSerializer.class);
 * serializer.parse(...);
 * </code>
 * <p/>
 * Where MyType1 and MyType2 are the types that your expecting to receive from the server.
 */
public abstract class AtmosphereGWTSerializer {
    
    protected com.kfuntak.gwt.json.serialization.client.Serializer jsonSerializer;

    public AtmosphereGWTSerializer() {
        if (getMode() == SerialMode.JSON
            || getPushMode() == SerialMode.JSON) {
            jsonSerializer = GWT.create(com.kfuntak.gwt.json.serialization.client.Serializer.class);
        }
    }
    
    public abstract SerialMode getMode();

    public abstract SerialMode getPushMode();

    public abstract Object deserialize(String message) throws SerializationException;
    
    protected Object deserializeRPC(String message) throws SerializationException {
        try {
            Serializer serializer = getRPCSerializer();
            ClientSerializationStreamReader reader = new ClientSerializationStreamReader(serializer);
            reader.prepareToRead(message);
            return reader.readObject();
        } catch (RuntimeException e) {
            throw new SerializationException(e);
        }
    }
    protected Object deserializeJSON(String message) throws SerializationException {
        return JsonSerializerUtil.deserialize(jsonSerializer, message);
    }
    protected Object deserializePLAIN(String message) throws SerializationException {
        return message;
    }


    public abstract String serialize(Object message) throws SerializationException;
    
    protected String serializeRPC(Object message) throws SerializationException {
            try {
                Serializer serializer = getRPCSerializer();
                ClientSerializationStreamWriter writer = new ClientSerializationStreamWriter(serializer, GWT.getModuleBaseURL(), GWT.getPermutationStrongName());
                writer.prepareToWrite();
                writer.writeObject(message);
                return writer.toString();
            } catch (RuntimeException e) {
                throw new SerializationException(e);
            }
    }
    protected String serializeJSON(Object message) throws SerializationException {
        return jsonSerializer.serialize(message);
    }
    protected String serializePLAIN(Object message) throws SerializationException {
        return message.toString();
    }


    protected abstract Serializer getRPCSerializer();

}
