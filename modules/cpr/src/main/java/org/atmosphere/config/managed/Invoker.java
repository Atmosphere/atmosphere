/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.config.managed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Invoke a method based on {@link Encoder} and {@link Decoder}
 *
 * @author Jeanfrancois Arcand
 */
public class Invoker {

    private final static Logger logger = LoggerFactory.getLogger(Invoker.class);

    public static Object invokeMethod(
            List<Encoder<?, ?>> encoders,
            List<Decoder<?, ?>> decoders,
            Object instanceType,
            Object objectToInvoke,
            Method method) {

        boolean hasMatch = false;
        instanceType = matchDecoder(instanceType, decoders);
        Object decodedObject = matchDecoder(instanceType, decoders);
        if (instanceType == null) {
            logger.trace("No Encoder matching {}", instanceType);
        }
        decodedObject = decodedObject == null ? instanceType : decodedObject;

        logger.trace("{} .on {}", method.getName(), decodedObject);
        Object objectToEncode = null;
        try {
            objectToEncode = method.invoke(objectToInvoke, new Object[]{decodedObject});
            hasMatch = true;
        } catch (Throwable e) {
            logger.trace("{} is trying to invoke {}", method.getName(), instanceType);
        }

        if (!hasMatch) {
            logger.trace("No Method's Arguments {} matching {}", method.getName(), instanceType);
        }

        Object encodedObject = matchEncoder(objectToEncode, encoders);
        if (encodedObject == null) {
            logger.trace("No Encoder matching {}", objectToEncode);
        }

        return encodedObject == null ? objectToEncode : encodedObject;
    }

    public static Object matchDecoder(Object instanceType, List<Decoder<?, ?>> decoders) {
        for (Decoder d : decoders) {
            Class<?>[] typeArguments = TypeResolver.resolveArguments(d.getClass(), Decoder.class);
            if (instanceType != null && typeArguments.length > 0 && typeArguments[0].equals(instanceType.getClass())) {

                logger.trace("{} is trying to decode {}", d, instanceType);
                instanceType = d.decode(instanceType);
            }
        }
        return instanceType;
    }

    public static Object matchEncoder(Object instanceType, List<Encoder<?, ?>> encoders) {
        for (Encoder d : encoders) {
            Class<?>[] typeArguments = TypeResolver.resolveArguments(d.getClass(), Encoder.class);
            if (instanceType != null && typeArguments.length > 0 && typeArguments[0].equals(instanceType.getClass())) {

                logger.trace("{} is trying to encode {}", d, instanceType);
                instanceType = d.encode(instanceType);
            }
        }
        return instanceType;
    }

}
