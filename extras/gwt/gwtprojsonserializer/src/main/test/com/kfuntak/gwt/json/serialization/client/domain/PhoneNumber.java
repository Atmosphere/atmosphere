package com.kfuntak.gwt.json.serialization.client.domain;

import com.kfuntak.gwt.json.serialization.client.JsonSerializable;

public class PhoneNumber implements JsonSerializable {

    private String number;
    private String ext;
    private String type;
    private String listedStatus;

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getExt() {
        return ext;
    }

    public void setExt(String ext) {
        this.ext = ext;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getListedStatus() {
        return listedStatus;
    }

    public void setListedStatus(String listedStatus) {
        this.listedStatus = listedStatus;
    }

    @Override
    public String toString() {
        StringBuffer buffer = new StringBuffer();
        buffer.append("{");
        buffer.append("number:");
        buffer.append(number + ",");
        buffer.append("ext:");
        buffer.append(ext + ",");
        buffer.append("type:");
        buffer.append(type + ",");
        buffer.append("listedStatus:");
        buffer.append(listedStatus + ",");
        buffer.append("}");
        return buffer.toString();
    }
}
