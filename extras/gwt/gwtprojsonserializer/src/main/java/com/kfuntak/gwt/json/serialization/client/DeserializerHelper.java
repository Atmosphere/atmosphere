package com.kfuntak.gwt.json.serialization.client;

import java.util.Date;

import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONException;
import com.google.gwt.json.client.JSONNull;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;

public class DeserializerHelper {

    public static String getString(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONString)) {
            throw new JSONException();
        } else {
            JSONString jsonString = (JSONString) value;
            return jsonString.stringValue();
        }
    }

    public static Character getChar(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONString)) {
            throw new JSONException();
        } else {
            JSONString jsonString = (JSONString) value;
            try {
                return jsonString.stringValue().charAt(0);
            } catch (IndexOutOfBoundsException e) {
                throw new JSONException();
            }
        }
    }

    public static Double getDouble(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONNumber)) {
            throw new JSONException();
        } else {
            JSONNumber jsonNumber = (JSONNumber) value;
            return jsonNumber.doubleValue();
        }
    }

    public static Float getFloat(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONNumber)) {
            throw new JSONException();
        } else {
            JSONNumber jsonNumber = (JSONNumber) value;
            return ((Double) jsonNumber.doubleValue()).floatValue();
        }
    }

    public static Integer getInt(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONNumber)) {
            throw new JSONException();
        } else {
            JSONNumber jsonNumber = (JSONNumber) value;
            return ((Double) jsonNumber.doubleValue()).intValue();
        }
    }

    public static Long getLong(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONNumber)) {
            throw new JSONException();
        } else {
            JSONNumber jsonNumber = (JSONNumber) value;
            return ((Double) jsonNumber.doubleValue()).longValue();
        }
    }

    public static Short getShort(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONNumber)) {
            throw new JSONException();
        } else {
            JSONNumber jsonNumber = (JSONNumber) value;
            return ((Double) jsonNumber.doubleValue()).shortValue();
        }
    }

    public static Byte getByte(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONNumber)) {
            throw new JSONException();
        } else {
            JSONNumber jsonNumber = (JSONNumber) value;
            return ((Double) jsonNumber.doubleValue()).byteValue();
        }
    }

    public static Boolean getBoolean(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONBoolean)) {
            throw new JSONException();
        } else {
            JSONBoolean jsonBoolean = (JSONBoolean) value;
            return jsonBoolean.booleanValue();
        }
    }

    public static Date getDate(JSONValue value) throws JSONException {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (!(value instanceof JSONString || value instanceof JSONNumber)) {
            throw new JSONException();
        }
        if (value instanceof JSONString) {
            try {
                long dateValue = Long.parseLong(((JSONString) value).stringValue());
                return new Date(dateValue);
            } catch (NumberFormatException e) {
                throw new JSONException();
            }
        }
        return new Date(new Double(((JSONNumber) value).doubleValue()).longValue());
    }
}
