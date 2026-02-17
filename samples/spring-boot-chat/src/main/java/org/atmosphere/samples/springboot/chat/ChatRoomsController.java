/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.samples.springboot.chat;

import org.atmosphere.room.RoomManager;
import org.atmosphere.room.RoomMember;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint exposing Atmosphere rooms information.
 *
 * <p>GET {@code /api/rooms} returns rooms with member counts and
 * member details, useful for building room-selection UIs.</p>
 */
@RestController
@RequestMapping("/api/rooms")
public class ChatRoomsController {

    private final RoomManager roomManager;

    public ChatRoomsController(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @GetMapping
    public List<Map<String, Object>> listRooms() {
        return roomManager.all().stream()
                .map(room -> {
                    var map = new HashMap<String, Object>();
                    map.put("name", room.name());
                    map.put("members", room.size());
                    map.put("destroyed", room.isDestroyed());

                    // Include member details from the Room Protocol
                    var memberList = room.memberInfo().values().stream()
                            .map(m -> {
                                var mMap = new HashMap<String, Object>();
                                mMap.put("id", m.id());
                                mMap.put("metadata", m.metadata());
                                return (Map<String, Object>) mMap;
                            })
                            .toList();
                    map.put("memberDetails", memberList);

                    return (Map<String, Object>) map;
                })
                .toList();
    }
}
