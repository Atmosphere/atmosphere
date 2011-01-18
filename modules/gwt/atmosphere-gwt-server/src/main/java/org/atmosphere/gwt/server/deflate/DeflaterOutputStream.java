// $Id: ZlibOutputStream.java 105 2007-08-07 07:45:45Z pornin $
/*
 * Copyright (c) 2007  Thomas Pornin
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.atmosphere.gwt.server.deflate;

import org.atmosphere.gwt.server.deflate.Deflater;
import java.io.IOException;
import java.io.OutputStream;

/**
 * <p>
 * This class implements a stream which compresses data into the <code>zlib</code> format (RFC 1950).
 * </p>
 * 
 * <p>
 * The compression level can be specified, as a symbolic value identical to what the <code>Deflater</code> class
 * expects. The default compression level is <code>MEDIUM</code>.
 * </p>
 * 
 * @version $Revision: 105 $
 * @author Thomas Pornin
 */

public class DeflaterOutputStream extends OutputStream {
	
	private Deflater deflater;
	
	/**
	 * Create the stream with the provided transport stream. The default compression level (<code>MEDIUM</code>) is
	 * used.
	 * 
	 * @param out
	 *            the transport stream
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	public DeflaterOutputStream(OutputStream out) throws IOException {
		this(out, Deflater.MEDIUM);
	}
	
	/**
	 * Create the stream with the provided transport stream. The provided compression level is used.
	 * 
	 * @param out
	 *            the transport stream
	 * @param level
	 *            the compression level
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	public DeflaterOutputStream(OutputStream out, int level) throws IOException {
		deflater = new Deflater(level);
		deflater.setOut(out);
	}
	
	/**
	 * Close this stream; the transport stream is also closed.
	 * 
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	@Override
	public void close() throws IOException {
		deflater.terminate();
		deflater.getOut().close();
	}
	
	/**
	 * Flush this stream; the transport stream is also flushed. At the DEFLATE level, a "sync flush" is performed, which
	 * ensures that output bytes written so far on the transport stream are sufficient to recover all the input bytes
	 * currently processed.
	 * 
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	@Override
	public void flush() throws IOException {
		deflater.flushSync(true);
		deflater.getOut().flush();
	}
	
	/** @see OutputStream */
	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}
	
	/** @see OutputStream */
	@Override
	public void write(byte[] buf) throws IOException {
		write(buf, 0, buf.length);
	}
	
	/** @see OutputStream */
	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		deflater.process(buf, off, len);
	}
}
