
package org.atmosphere.gwt.server.impl;

import com.google.gwt.rpc.server.ClientOracle;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.List;

/**
 *
 * @author p.havelaar
 */
abstract public class ManagedStreamResponseWriter extends GwtResponseWriterImpl {

	private CountOutputStream countOutputStream;
	private boolean refresh;

	protected Integer length;

	protected final boolean chrome;

	public ManagedStreamResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
		super(resource, serializationPolicy, clientOracle);

		String userAgent = resource.getAtmosphereResource().getRequest().getHeader("User-Agent");
		chrome = userAgent != null && userAgent.contains("Chrome");
	}

	@Override
	protected OutputStream getOutputStream(OutputStream outputStream) {
		countOutputStream = new CountOutputStream(outputStream);
		return countOutputStream;
	}

	@Override
	protected void doSuspend() throws IOException {
		int paddingRequired;
		String paddingParameter = getRequest().getParameter("padding");
		if (paddingParameter != null) {
			paddingRequired = Integer.parseInt(paddingParameter);
		}
		else {
			paddingRequired = getPaddingRequired();
		}

		String lengthParameter = getRequest().getParameter("length");
		if (lengthParameter != null) {
			length = Integer.parseInt(lengthParameter);
		}

		if (paddingRequired > 0) {
			countOutputStream.setIgnoreFlush(true);
			writer.flush();

			int written = countOutputStream.getCount();
            
			if (paddingRequired > written) {
				CharSequence padding = getPadding(paddingRequired - written);
				if (padding != null) {
					writer.append(padding);
				}
			}

			countOutputStream.setIgnoreFlush(false);
		}
	}

	@Override
	public synchronized void write(List<? extends Serializable> messages, boolean flush) throws IOException {
		super.write(messages, flush);
		checkLength();
	}

	@Override
	public synchronized void heartbeat() throws IOException {
		super.heartbeat();
		checkLength();
	}

	private void checkLength() throws IOException {
		int count = countOutputStream.getCount();
        // Chrome seems to have a problem with lots of small messages consuming lots of memory.
        // I'm guessing for each readyState = 3 event it copies the responseText from its IO system to its
        // JavaScript
        // engine and does not clean up all the events until the HTTP request is finished.
        if (chrome) {
            count = 2 * count;
        }
        if (!refresh && isOverRefreshLength(count)) {
            refresh = true;
            doRefresh();
        }
	}

	protected abstract void doRefresh() throws IOException;

	protected abstract int getPaddingRequired();

	protected abstract CharSequence getPadding(int padding);

	protected abstract boolean isOverRefreshLength(int written);
}
