/*
* Copyright 2011 Jeanfrancois Arcand
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

import com.google.gwt.core.client.GWT;
import com.google.gwt.rpc.client.impl.ClientWriterFactory;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.SerializationStreamReader;
import com.google.gwt.user.client.rpc.impl.ClientSerializationStreamReader;
import com.google.gwt.user.client.rpc.impl.ClientSerializationStreamWriter;
import com.google.gwt.user.client.rpc.impl.Serializer;

import java.io.Serializable;

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

    @SuppressWarnings("unchecked")
    public <T extends Serializable> T parse(String message) throws SerializationException {
        if (getMode() == SerialMode.RPC) {
            try {
                Serializer serializer = getSerializer();
                ClientSerializationStreamReader reader = new ClientSerializationStreamReader(serializer);
                reader.prepareToRead(message);
                return (T) reader.readObject();
            } catch (RuntimeException e) {
                throw new SerializationException(e);
            }
        } else if (getMode() == SerialMode.DE_RPC) {
            try {
                SerializationStreamReader reader = ClientWriterFactory.createReader(message);
                return (T) reader.readObject();
            } catch (RuntimeException e) {
                throw new SerializationException(e);
            }
        } else if (getMode() == SerialMode.PLAIN) {
            return (T) message;
        } else {
            throw new UnsupportedOperationException("Not implemented yet");
        }
    }


    @SuppressWarnings("unchecked")
    public <T extends Serializable> String serialize(T message) throws SerializationException {
        if (getPushMode() == SerialMode.RPC) {
            try {
                Serializer serializer = getSerializer();
                ClientSerializationStreamWriter writer = new ClientSerializationStreamWriter(serializer, GWT.getModuleBaseURL(), GWT.getPermutationStrongName());
                writer.prepareToWrite();
                writer.writeObject(message);
                return writer.toString();
            } catch (RuntimeException e) {
                throw new SerializationException(e);
            }
        } else if (getPushMode() == SerialMode.PLAIN) {
            return message.toString();
        } else {
            throw new UnsupportedOperationException("Not implemented yet");
        }
    }


    protected abstract Serializer getSerializer();

    public abstract SerialMode getMode();

    public abstract SerialMode getPushMode();
}
