package com.kfuntak.gwt.json.serialization.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.junit.client.GWTTestCase;
import com.kfuntak.gwt.json.serialization.client.domain.University;

;

public class SerializerTest extends GWTTestCase {

    /**
     * Must refer to a valid module that sources this class.
     */
    public String getModuleName() {
        return "com.kfuntak.gwt.json.serialization.GWTProJsonSerializer";
    }

    public void testSerialization() {

        Serializer serializer = (Serializer) GWT.create(Serializer.class);

        String jsonText = "{"
                + "\"class\":\"org.sagarius.gwt.json.client.example.pojo.University\""
                + ",\"contactInfo\":"
                + "[{\"address\":"
                + "{\"city\":\"Coimbatore\","
                + "\"class\":\"org.sagarius.gwt.json.example.client.pojo.Address\","
                + "\"country\":\"India\","
                + "\"line1\":\"Ganapathy\","
                + "\"line2\":null,"
                + "\"state\":\"Tamilnadu\","
                + "\"zipCode\":\"641030\"},"
                + "\"class\":\"org.sagarius.gwt.json.example.client.pojo.Contact\","
                + "\"phoneNumber\":{\"class\":\"org.sagarius.gwt.json.example.client.pojo.PhoneNumber\","
                + "\"ext\":null,"
                + "\"listedStatus\":null,"
                + "\"number\":\"00-000\","
                + "\"type\":null},"
                + "\"refId\":null},"
                + "{\"address\":"
                + "{\"city\":\"Udumalpet\","
                + "\"class\":\"org.sagarius.gwt.json.example.client.pojo.Address\","
                + "\"country\":\"India\","
                + "\"line1\":\"Dhandapani LO\","
                + "\"line2\":null,"
                + "\"state\":\"Tamilnadu\","
                + "\"zipCode\":\"642120\"},"
                + "\"class\":\"org.sagarius.gwt.json.example.client.pojo.Contact\","
                + "\"phoneNumber\":{\"class\":\"org.sagarius.gwt.json.example.client.pojo.PhoneNumber\","
                + "\"ext\":null,"
                + "\"listedStatus\":null,"
                + "\"number\":\"04252-210014\","
                + "\"type\":null},"
                + "\"refId\":null}],"
                + "\"forVerification\":\"Really for verification\","
                + "\"gradeLevels\":[\"12\",\"11\"],"
                + "\"refId\":\"cms\","
                + "\"refIdKey\":null,"
                + "\"schoolName\":\"CMS\","
                + "\"schoolShortName\":null,"
                + "\"schoolUrl\":\"http://cms.in\","
                + "\"startDate\":1252046885585,"
                + "\"status\":11}";

        try {
            University university = (University) serializer.deSerialize(jsonText, "com.kfuntak.gwt.json.serialization.client.domain.University");
            if (university != null) {
                System.out.println(university.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
