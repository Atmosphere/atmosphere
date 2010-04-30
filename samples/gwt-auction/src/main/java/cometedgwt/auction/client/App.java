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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.ClickListener;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.KeyboardListener;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import cometedgwt.auction.entity.AuctionItem;

/** 
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class App implements EntryPoint {

    public static final String TOPIC = "bids";
    private StreamingService streamingService;
    private Map mapOfItemPrices = new HashMap();
    private Map mapOfNumberOfBids = new HashMap();

    //
    //Ajax Push happens here!
    //
    private class BidCallback implements AsyncCallback {

        public void onFailure(Throwable throwable) {
            Window.alert(throwable.getMessage());
        }

        public void onSuccess(Object result) {

            JSONObject resultObject = (JSONObject) result;
            JSONArray resultArray = (JSONArray) resultObject.get("value");
            JSONNumber itemId = (JSONNumber) resultArray.get(0);
            JSONNumber itemPrice = (JSONNumber) resultArray.get(1);
            JSONNumber numberOfBids = (JSONNumber) resultArray.get(2);

            Integer itemKey = new Integer((int) itemId.getValue());
            ((Label) mapOfItemPrices.get(itemKey)).setText("$ " + itemPrice.toString());
            ((Label) mapOfNumberOfBids.get(itemKey)).setText("" + Double.valueOf(numberOfBids.toString()).intValue());
        }
    }

    /**
     * This is the entry point method.
     */
    public void onModuleLoad() {

        List itens = getAuctionItens();
        Grid table = new Grid(itens.size() + 1, 6);
        table.setStylePrimaryName("corpo");

        table.setText(0, 0, "Item Name");
        table.setText(0, 1, "# of bids");
        table.setText(0, 2, "Price");
        table.setText(0, 3, "My bid");

        for (int i = 0; i < itens.size(); i++) {

            final AuctionItem item = (AuctionItem) itens.get(i);

            final int itemId = item.getId();
            final Label labelNumberOfBids = new Label(String.valueOf(item.getNumberOfBids()));
            final Label labelPrice = new Label("$ " + String.valueOf(item.getPrice()));
            final TextBox txtBoxMyBid = new TextBox();
            final Button bidButton = new Button("Bid!");
            final Label labelMessage = new Label("");

            bidButton.setStylePrimaryName("principal");

            //Save itemPrice Label to be used when new bids are processed.
            mapOfItemPrices.put(new Integer(itemId), labelPrice);
            //Save numberOfBids Label to be used when new bids are processed.
            mapOfNumberOfBids.put(new Integer(itemId), labelNumberOfBids);

            //Handle ENTER key
            txtBoxMyBid.addKeyboardListener(new KeyboardListener() {

                public void onKeyUp(Widget sender, char keyCode, int modifiers) {
                    if (keyCode == '\r') {
                        sendNewBid(item, txtBoxMyBid, labelMessage);
                    }
                }

                public void onKeyDown(Widget sender, char keyCode, int modifiers) {
                }

                public void onKeyPress(Widget sender, char keyCode, int modifiers) {
                }
            });

            //Handle button click
            bidButton.addClickListener(new ClickListener() {

                public void onClick(Widget sender) {
                    sendNewBid(item, txtBoxMyBid, labelMessage);
                }
            });

            table.setText(i + 1, 0, item.getName());
            table.setWidget(i + 1, 1, labelNumberOfBids);
            table.setWidget(i + 1, 2, labelPrice);
            table.setWidget(i + 1, 3, txtBoxMyBid);
            table.setWidget(i + 1, 4, bidButton);
            table.setWidget(i + 1, 5, labelMessage);

        }

        RootPanel.get("slot1").add(table);

        streamingService = StreamingServiceGWTClientImpl.getInstance();
        streamingService.subScribeToEvent(TOPIC, new BidCallback());

    }

    private List getAuctionItens() {

        //TODO Get them from server side.

        AuctionItem item1 = new AuctionItem(0, "???? Nokia N80", 100.0f);
        AuctionItem item2 = new AuctionItem(1, "Laptop Apple PowerBook G4 17''", 1050.0f);
        AuctionItem item3 = new AuctionItem(2, "Canon Rebel XT", 800.0f);

        List itens = new ArrayList();
        itens.add(item1);
        itens.add(item2);
        itens.add(item3);

        return itens;
    }

    private void sendNewBid(AuctionItem item, TextBox myBid, Label message) {

        int itemId = item.getId();
        double lastBid = item.getPrice();

        String newBid = myBid.getText();
        double newBidValue = 0.0;

        try {
            newBidValue = Double.parseDouble(newBid);
        } catch (NumberFormatException e) {
            message.setText("Not a valid bid");
            return;
        }

        if (newBidValue < lastBid) {
            message.setText("Not a valid bid");
            return;
        }

        message.setText("");

        item.setPrice(newBidValue);
        int numberOfBids = item.getNumberOfBids();

        JSONArray array = new JSONArray();
        array.set(0, new JSONNumber(itemId));
        array.set(1, new JSONNumber(newBidValue));
        array.set(2, new JSONNumber(numberOfBids));

        JSONObject container = new JSONObject();
        container.put("value", array);

        streamingService.sendMessage(TOPIC, container);
        myBid.setText("");
        myBid.setFocus(true);
    }
}
