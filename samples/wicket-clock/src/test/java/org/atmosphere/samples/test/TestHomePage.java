package org.atmosphere.samples.test;

import junit.framework.TestCase;
import org.apache.wicket.util.tester.WicketTester;
import org.atmosphere.samples.wicket.HomePage;
import org.atmosphere.samples.wicket.WicketPushApplication;

/**
 * Simple test using the WicketTester
 */
public class TestHomePage extends TestCase {
    private WicketTester tester;

    @Override
    public void setUp() throws InstantiationException, IllegalAccessException {
        tester = new WicketTester(new WicketPushApplication());
    }

    public void testRenderMyPage() {
        //start and render the test page
        tester.startPage(HomePage.class);

        //assert rendered page class
        tester.assertRenderedPage(HomePage.class);

        //assert rendered label component
        tester.assertLabel("message", "This clock updates the time using Atmosphere Meteor PUSH framework");
    }
}
