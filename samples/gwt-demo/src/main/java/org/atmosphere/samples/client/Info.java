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
package org.atmosphere.samples.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.ui.Widget;
import java.util.ArrayList;

/**
 *
 * @author p.havelaar
 */
public class Info extends PopupPanel {

    public static void display(String title, String message) {

        final Info info = new Info(title, message);

        info.show();
        
        Timer t = new Timer() {
            @Override
            public void run() {
                info.hide();
            }
        };
        t.schedule(4000);
    }

    @Override
    public void show() {
        super.show();
        slots.add(level, this);
    }


    @Override
    public void hide() {
        super.hide();
        slots.set(level, null);
    }


    protected Info(String title, String message) {

        add(new InfoWidget(title, message));
        setWidth("300px");
        setHeight("50px");

        int root_width = Window.getClientWidth();
        int root_height = Window.getClientHeight();

        level = findAvailableLevel();

        int left = root_width - 320;
        int top = root_height - 80 - (level * 60);

        setPopupPosition(left, top);
    }

    private static ArrayList<Info> slots = new ArrayList<Info>();

    private int level;

    private static int findAvailableLevel() {
        int size = slots.size();
        for (int i=0; i<size; i++) {
            if (slots.get(i) == null) {
                return i;
            }
        }
        return size;
    }
    
    public static class InfoWidget extends Composite {
        MyUiBinder binder = GWT.create(MyUiBinder.class);
        interface MyUiBinder extends UiBinder<Widget, InfoWidget> {}
        @UiField
        Label title;
        @UiField
        Label message;
        
        private InfoWidget(String title, String message) {
            initWidget(binder.createAndBindUi(this));
            this.title.setText(title);
            this.message.setText(message);
        }
    }



}
