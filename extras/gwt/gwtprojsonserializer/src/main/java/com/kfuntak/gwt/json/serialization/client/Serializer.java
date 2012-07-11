package com.kfuntak.gwt.json.serialization.client;

import java.util.HashMap;
import java.util.Map;

import com.google.gwt.json.client.JSONException;
import com.google.gwt.json.client.JSONValue;

public class Serializer {

    private static Map SERIALIZABLE_TYPES;

    private static Map serializableTypes() {
        if (SERIALIZABLE_TYPES == null) {
            SERIALIZABLE_TYPES = new HashMap();
        }
        return SERIALIZABLE_TYPES;
    }

    protected void addObjectSerializer(String name, ObjectSerializer obj) {
        serializableTypes().put(name, obj);
    }

    protected ObjectSerializer getObjectSerializer(String name) {
        return (ObjectSerializer) serializableTypes().get(name);
    }

    protected Serializer() {
    }

    static protected String getTypeName(Object obj) {
        // WARNING: GWT.getTypeName is deprecated
        //String typeName = GWT.getTypeName( obj );
        //typeName = typeName.substring(typeName.lastIndexOf('.')+1);
        //return typeName.toLowerCase();
        String typeName = obj.getClass().getName();
        return typeName;
    }

    public String serialize(Object pojo) {
        String name = getTypeName(pojo);
        ObjectSerializer serializer = getObjectSerializer(name);
        if (serializer == null) {
            throw new SerializationException("Can't find object serializer for " + name);
        }
        return serializer.serialize(pojo);
    }

    public JSONValue serializeToJson(Object pojo) {
        String name = getTypeName(pojo);
        ObjectSerializer serializer = getObjectSerializer(name);
        if (serializer == null) {
            throw new SerializationException("Can't find object serializer for " + name);
        }
        return serializer.serializeToJson(pojo);
    }

    public Object deSerialize(JSONValue jsonValue, String className) throws JSONException {
        ObjectSerializer serializer = getObjectSerializer(className);
        if (serializer == null) {
            throw new SerializationException("Can't find object serializer for " + className);
        }
        return serializer.deSerialize(jsonValue, className);
    }

    public Object deSerialize(String jsonString, String className) throws JSONException {
        ObjectSerializer serializer = getObjectSerializer(className);
        if (serializer == null) {
            throw new SerializationException("Can't find object serializer for " + className);
        }
        return serializer.deSerialize(jsonString, className);
    }
}
