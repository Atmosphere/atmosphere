/**
 * The MIT License
 * Copyright (c) 2010 Tad Glines
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.atmosphere.protocol.socketio.transport;

public enum DisconnectReason {
	UNKNOWN(-1),
	CONNECT_FAILED(1),	// A connection attempt failed.
	DISCONNECT(2),		// Disconnect was called explicitly.
	TIMEOUT(3),			// A timeout occurred.
	CLOSE_FAILED(4),	// The connection dropped before an orderly close could complete.
	ERROR(5),			// A GET or POST returned an error, or an internal error occurred.
	CLOSED_REMOTELY(6),	// Remote end point initiated a close.
	CLOSED(6);			// Locally initiated close succeeded.

	private int value;
	private DisconnectReason(int v) { this.value = v; }
	public int value() { return value; }
	
	public static DisconnectReason fromInt(int val) {
		switch (val) {
		case 1:
			return CONNECT_FAILED;
		case 2:
			return DISCONNECT;
		case 3:
			return TIMEOUT;
		case 4:
			return CLOSE_FAILED;
		case 5:
			return ERROR;
		case 6:
			return CLOSED_REMOTELY;
		case 7:
			return CLOSED;
		default:
			return UNKNOWN;
		}
	}
}