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
package org.atmosphere.samples.chat;

import org.atmosphere.cpr.Broadcaster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class ChatProtocol implements JacksonEncoder.Encodable {

    private String message;
    private String author;
    private long time;
    private List<String> users = new ArrayList<String>();
    private List<String> rooms = new ArrayList<String>();
    private String uuid;

    public ChatProtocol() {
        this("", "");
    }

    public ChatProtocol(String author, String message) {
        this.author = author;
        this.message = message;
        this.time = new Date().getTime();
    }

    public ChatProtocol(String author, String message, Collection<String> users, Collection<Broadcaster> rooms) {
        this(author, message);
        this.users.addAll(users);
        for(Broadcaster b: rooms) {
            this.rooms.add(b.getID().toString());
        }
    }

    public ChatProtocol(Collection<String> users, Collection<Broadcaster> rooms) {
        this.users.addAll(users);
        for(Broadcaster b: rooms) {
            this.rooms.add(b.getID().toString());
        }
    }

    public String getMessage() {
        return message;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public List<String> getUsers() {
        return users;
    }

    public void setUsers(Collection<String> users) {
        this.users.addAll(users);
    }

    public void setUsers(List<String> users) {
        this.users = users;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public List<String> getRooms() {
        return rooms;
    }

    public void setRooms(List<String> rooms) {
        this.rooms = rooms;
    }

}
