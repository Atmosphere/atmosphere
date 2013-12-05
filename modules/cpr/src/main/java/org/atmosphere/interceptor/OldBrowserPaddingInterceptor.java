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
package org.atmosphere.interceptor;

/**
 * Old 8k Padding interceptor for Browser that needs whitespace when streaming is used.
 *
 * @author Jeanfrancois Arcand
 */
public class OldBrowserPaddingInterceptor extends PaddingAtmosphereInterceptor {

    public OldBrowserPaddingInterceptor() {
        super(8192);
    }

}
