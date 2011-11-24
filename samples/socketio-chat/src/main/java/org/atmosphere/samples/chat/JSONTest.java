package org.atmosphere.samples.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

public class JSONTest {

	/**
	 * @param args
	 * @throws IOException 
	 * @throws JsonMappingException 
	 * @throws Exception 
	 */
	public static void main(String[] args) throws Exception {
		
		
		// {"args":[{"user1":"user1","user3":"user3","user2":"user2"}],"name":"nicknames"}
		
		String message = "{\"args\":[{\"user1\":\"user1\",\"user3\":\"user3\",\"user2\":\"user2\"}],\"name\":\"nicknames\"}";
		
		ObjectMapper mapper = new ObjectMapper();

		ChatJSONObject chat = mapper.readValue(message, ChatJSONObject.class);
		
		System.out.println(chat);
		
		message = "{\"name\":\"user message\",\"args\":[\"user1 dit allo\"]}";
		chat = mapper.readValue(message, ChatJSONObject.class);
		
		System.out.println(chat);
		
		message = "{\"args\":[\"user1\",\"user1 dit allo\"],\"name\":\"user message\"}";
		chat = mapper.readValue(message, ChatJSONObject.class);
		
		System.out.println(chat);
		
		
		// login
		
		message = "{\"name\":\"nickname\",\"args\":[\"test\"]}";
		chat = mapper.readValue(message, ChatJSONObject.class);
		
		System.out.println(chat);
		
		ConcurrentMap<String, String> loggedUserMap = new ConcurrentSkipListMap<String, String>();
		
		
		loggedUserMap.put("user1", "user1");
		loggedUserMap.put("user2", "user2");
		loggedUserMap.put("user3", "user3");
		loggedUserMap.put("user4", "user4");
		loggedUserMap.put("user5", "user5");
		
		
		System.out.println(mapper.writeValueAsString(loggedUserMap.values()));
		
		ChatJSONObject out = new ChatJSONObject();
		
		out.setName("nicknames");
		out.setArgs(loggedUserMap.values());
		
		System.out.println(mapper.writeValueAsString(out));
		
		
		loggedUserMap = new ConcurrentSkipListMap<String, String>();
		
		
		loggedUserMap.put("user1", "user1");
		loggedUserMap.put("user2", "user2");
		loggedUserMap.put("user3", "user3");
		loggedUserMap.put("user4", "user4");
		loggedUserMap.put("user5", "user5");
		
		List list = new ArrayList();
		
		list.add(loggedUserMap);
		
		out = new ChatJSONObject();
		
		out.setName("nicknames");
		out.setArgs(list);
		
		System.out.println(mapper.writeValueAsString(out));
		
	}

}
