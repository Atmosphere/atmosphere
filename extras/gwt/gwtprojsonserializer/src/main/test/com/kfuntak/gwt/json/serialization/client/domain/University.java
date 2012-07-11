package com.kfuntak.gwt.json.serialization.client.domain;

import com.kfuntak.gwt.json.serialization.client.JsonSerializable;

public class University extends School implements JsonSerializable {

    private String forVerification;

    public void setForVerification(String forVerification) {
        this.forVerification = forVerification;
    }

    public String getForVerification() {
        return forVerification;
    }

    @Override
    public String toString() {
        StringBuffer buffer = new StringBuffer();
        buffer.append("{");
        buffer.append(super.toString());
        buffer.append(",");
        buffer.append("forVerification:");
        buffer.append(forVerification + ",");
        buffer.append("}");
        return buffer.toString();
    }
}
