package com.kfuntak.gwt.json.serialization.client;

import com.google.gwt.json.client.JSONException;
import com.google.gwt.json.client.JSONValue;

public interface ObjectSerializer {

    String serialize(Object pojo);

    JSONValue serializeToJson(Object pojo);

    Object deSerialize(JSONValue jsonValue, String className) throws JSONException;

    Object deSerialize(String jsonString, String className) throws JSONException;
}
