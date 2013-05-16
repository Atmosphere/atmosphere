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
package org.atmosphere.samples.chat;

import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple object that contains all the created rooms.
 *
 * @author Jeanfrancois Arcand
 */
public class RoomsState implements JacksonEncoder.Encodable {

    private List<String> rooms = new ArrayList<String>();
    private List<String> users = new ArrayList<String>();

    public RoomsState(BroadcasterFactory factory) {
        for (Broadcaster b : factory.lookupAll()) {
            rooms.add(b.getID().toString());
        }
    }

    public List<String> getUsers() {
        return users;
    }

    public void setUsers(List<String> users) {
        this.users = users;
    }

    public List<String> getRooms() {
        return rooms;
    }

    public void setRooms(List<String> rooms) {
        this.rooms = rooms;
    }

    public void addRoom(String room) {
        rooms.add(room);
    }

    public void addUser(String user) {
        users.add(user);
    }

}
