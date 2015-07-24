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
package org.atmosphere.cpr;


/**
 * The KeepOpenStreamAware interface is used by a class implementing the {@link AsyncIOWriter} interface
 * to indicate the closing of the underlining stream is managed elsewhere and invoking the 
 * {@link AsyncIOWriter#close(AtmosphereResponse)} method does not close the stream. In other words, 
 * the underlining stream will be kept open until it is closed by other means.
 */
public interface KeepOpenStreamAware {
}
