/*
 * Copyright 2013 Sebastien Dionne
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
package org.atmosphere.samples.chat;

import java.util.ArrayList;
import java.util.Collection;

/**
 * @author Sebastien Dionne : sebastien.dionne@gmail.com
 */
public class ChatJSONObject {

    public static final String LOGIN = "nickname";
    public static final String USERCONNECTEDLIST = "nicknames";
    public static final String MESSAGE = "user message";
    public static final String ANNONCEMENT = "announcement";

    public String name;
    public Collection args = new ArrayList();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Collection getArgs() {
        return args;
    }

    public void setArgs(Collection args) {
        this.args = args;
    }

    @Override
    public String toString() {
        return "ChatJSONObject [name=" + name + ", args=" + args + "]";
    }

}
