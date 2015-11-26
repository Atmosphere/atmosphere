/*
 * Copyright 2015 Async-IO.org
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Invoke a method based on {@link Encoder} and {@link Decoder}
 *
 * @author Jeanfrancois Arcand
 */
public class Invoker {

    private final static Logger logger = LoggerFactory.getLogger(Invoker.class);

    public static Object decode(
            List<Decoder<?, ?>> decoders,
            Object instanceType) {

        Object decodedObject = matchDecoder(instanceType, decoders);
        if (instanceType == null) {
            logger.trace("No Encoder matching {}", instanceType);
        }
        return decodedObject;
    }

    public static Object invokeMethod(Method method, Object objectToInvoke, Object ... parameters) {
        Object objectToEncode = null;
        boolean hasMatch = false;
        try {
            objectToEncode = method.invoke(objectToInvoke, method.getParameterTypes().length == 0 ? new Object[]{} : parameters);
            hasMatch = true;
        } catch (IllegalAccessException e) {
            logger.trace("", e);
        } catch (InvocationTargetException e) {
            logger.error("", e);
        } catch (java.lang.IllegalArgumentException e) {
            logger.trace("", e);
        } catch (Throwable e) {
            logger.error("", e);
        }

        if (!hasMatch) {
            logger.trace("No Method's Arguments {} matching {}", method.getName(), objectToInvoke);
        }
        return objectToEncode;
    }

    public static Object encode(List<Encoder<?, ?>> encoders, Object objectToEncode) {
        Object encodedObject = matchEncoder(objectToEncode, encoders);
        if (encodedObject == null) {
            logger.trace("No Encoder matching {}", objectToEncode);
        }
        return encodedObject;
    }

    public static Object all(
            List<Encoder<?, ?>> encoders,
            List<Decoder<?, ?>> decoders,
            Object instanceType,
            Object objectToInvoke,
            Method method) {

        Object decodedObject = decode(decoders, instanceType);
        if (instanceType == null) {
            logger.trace("No Encoder matching {}", instanceType);
        }
        decodedObject = decodedObject == null ? instanceType : decodedObject;

        logger.trace("{} .on {}", method.getName(), decodedObject);
        Object objectToEncode = invokeMethod(method, objectToInvoke, decodedObject);

        Object encodedObject = null;
        if (objectToEncode != null) {
            encodedObject = encode(encoders, objectToEncode);
        }
        return encodedObject == null ? objectToEncode : encodedObject;
    }

    public static Object matchDecoder(Object instanceType, List<Decoder<?, ?>> decoders) {
        Object decodedObject = decoders.isEmpty() ? instanceType : null;
        for (Decoder d : decoders) {
            Class<?>[] typeArguments = TypeResolver.resolveArguments(d.getClass(), Decoder.class);
            if (instanceType != null && typeArguments.length > 0 && typeArguments[0].isAssignableFrom(instanceType.getClass())) {

                logger.trace("{} is trying to decode {}", d, instanceType);
                try {
                    decodedObject = d.decode(instanceType);
                } catch (Exception e) {
                    logger.trace("", e);
                }
            }
        }
        return decodedObject;
    }

    public static Object matchEncoder(Object instanceType, List<Encoder<?, ?>> encoders) {
        if (instanceType == null) return null;

        Object encodedObject = encoders.isEmpty() ? instanceType : null;
        for (Encoder d : encoders) {
            Class<?>[] typeArguments = TypeResolver.resolveArguments(d.getClass(), Encoder.class);
            if (instanceType != null && typeArguments.length > 0 && typeArguments[0].isAssignableFrom(instanceType.getClass())) {
                logger.trace("{} is trying to encode {}", d, instanceType);
                encodedObject = d.encode(instanceType);
            }
        }
        return encodedObject;
    }
}
