package org.atmosphere.samples.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

/**
 *
 * @author p.havelaar
 */
public class InfoWidget extends Composite {
    MyUiBinder binder = GWT.create(MyUiBinder.class);

    interface MyUiBinder extends UiBinder<Widget, InfoWidget> {
    }
    @UiField
    Label title;
    @UiField
    HTML message;

    InfoWidget(String title, String message) {
        initWidget(binder.createAndBindUi(this));
        this.title.setText(title);
        this.message.setHTML(message);
    }
    
}
