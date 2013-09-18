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

/**
 * Decode a message in order to invoke a class annotated with {@link org.atmosphere.config.service.ManagedService} with a method
 * annotated with {@link org.atmosphere.config.service.Message}. For example:
 * <blockquote><pre>

     public final class StringBufferDecoder implements Decoder<String, StringBuffer> {

         @Override
         public StringBuffer decode(String s) {
             return  new StringBuffer(s);
         }
     }
 * </pre></blockquote>
 * will decode a String into a StringBuffer. The decoded object will then be used to invoke a method annotated with @Message
 * <blockquote><pre>

     @Message(decoders = {StringBufferDecoder.class})
     public void message(StringBuffer m) {
         message.set(m.toString());
     }
 * </pre></blockquote>
 * You can chain Decoders. They will be invoked in the order they are defined and the last decoded value will be used to invoke the
 * @Message annotated method.
 * @param <U>
 * @param <T>
 * @author Jeanfrancois Arcand
 */
public interface Decoder<U, T> {
    /**
     * Decode the specified object of type U into object of type T
     *
     * @param s a object of type U
     * @return a new object of type T
     */
    T decode(U s);

}
