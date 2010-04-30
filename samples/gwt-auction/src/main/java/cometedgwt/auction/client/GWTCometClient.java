/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package cometedgwt.auction.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.ClickListener;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

/**
 * Example class that demostrates the usage of the StreamingService framework
 * 
 */
public class GWTCometClient implements EntryPoint {

    final private Button buttonSendMessage = new Button("Send Message");
    final private TextArea textAreaHistory = new TextArea();
    final private TextBox textBoxNewMessage = new TextBox();
    final private Button buttonSubscribe = new Button("Subscribe");
    final private TextBox textBoxTopic = new TextBox();
    StreamingService streamingService;

    private class KeepAliveCallback implements AsyncCallback {

        public void onFailure(Throwable throwable) {
            Window.alert(throwable.getMessage());
        }

        public void onSuccess(Object result) {
            if (!buttonSendMessage.isEnabled()) {
                buttonSubscribe.setEnabled(true);
            }
        }
    }

    private class ChatCallback implements AsyncCallback {

        private final String queueName;

        public ChatCallback(String queueName) {
            this.queueName = queueName;
        }

        public void onFailure(Throwable throwable) {
            Window.alert(throwable.getMessage());
        }

        public void onSuccess(Object result) {

            JSONObject resultObject = (JSONObject) result;
            JSONArray resultArray = (JSONArray) resultObject.get("value");
            JSONString resultString = (JSONString) resultArray.get(0);

            StringBuffer newText = new StringBuffer(textAreaHistory.getText());
            newText.append(queueName);
            newText.append(':');
            newText.append(resultString.stringValue());
            newText.append('\n');
            textAreaHistory.setText(newText.toString());
            textAreaHistory.setCursorPos(textAreaHistory.getVisibleLines());
        }
    }

    public void onModuleLoad() {

        buttonSendMessage.addClickListener(new ClickListener() {

            public void onClick(Widget sender) {
                if (!"".equals(textBoxNewMessage.getText())) {
                    JSONArray array = new JSONArray();
                    array.set(0, new JSONString(textBoxNewMessage.getText()));

                    JSONObject container = new JSONObject();
                    container.put("value", array);

                    streamingService.sendMessage(textBoxTopic.getText(), container);
                    textBoxNewMessage.setText("");
                    textBoxNewMessage.setFocus(true);
                }
            }
        });
        buttonSendMessage.setEnabled(false);

        buttonSubscribe.addClickListener(new ClickListener() {

            public void onClick(Widget sender) {
                if (!"".equals(textBoxTopic.getText())) {
                    streamingService.subScribeToEvent(textBoxTopic.getText(), new ChatCallback(textBoxTopic.getText()));
                    buttonSendMessage.setEnabled(true);
                }
            }
        });
        buttonSubscribe.setEnabled(false);

        textAreaHistory.setSize("300", "300");
        textAreaHistory.setEnabled(false);

        textBoxTopic.setText("chat");

        RootPanel.get("slot1").add(textAreaHistory);
        RootPanel.get("slot2").add(textBoxNewMessage);
        RootPanel.get("slot3").add(buttonSendMessage);
        RootPanel.get("slot4").add(textBoxTopic);
        RootPanel.get("slot5").add(buttonSubscribe);

        streamingService = StreamingServiceGWTClientImpl.getInstance();

        streamingService.subScribeToEvent("keepAlive", new KeepAliveCallback());
    }
}
