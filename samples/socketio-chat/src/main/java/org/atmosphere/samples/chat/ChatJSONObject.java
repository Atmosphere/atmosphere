package org.atmosphere.samples.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
