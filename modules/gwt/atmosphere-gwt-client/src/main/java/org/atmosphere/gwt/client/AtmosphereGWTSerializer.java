/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
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
import java.io.Serializable;

import com.google.gwt.rpc.client.impl.ClientWriterFactory;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.SerializationStreamReader;
import com.google.gwt.user.client.rpc.impl.ClientSerializationStreamReader;
import com.google.gwt.user.client.rpc.impl.ClientSerializationStreamWriter;
import com.google.gwt.user.client.rpc.impl.Serializer;

/**
 * The base class for comet serializers. To instantiate this class follow this example:
 * 
 * <code>
 * @SerialTypes({ MyType1.class, MyType2.class })
 * public static abstract class MyCometSerializer extends CometSerializer {}
 * 
 * CometSerializer serializer = GWT.create(MyCometSerializer.class);
 * serializer.parse(...);
 * </code>
 * 
 * Where MyType1 and MyType2 are the types that your expecting to receive from the server.
 * 
 * @author Richard Zschech
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
			}
			catch (RuntimeException e) {
				throw new SerializationException(e);
			}
		}
		else if (getMode() == SerialMode.DE_RPC) {
			try {
				SerializationStreamReader reader = ClientWriterFactory.createReader(message);
				return (T) reader.readObject();
			}
			catch (RuntimeException e) {
				throw new SerializationException(e);
			}
		} else if (getMode() == SerialMode.PLAIN) {
            return (T) message;
        }  else {
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
			}
			catch (RuntimeException e) {
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
