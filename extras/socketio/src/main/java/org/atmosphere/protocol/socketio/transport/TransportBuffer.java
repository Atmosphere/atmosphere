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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TransportBuffer {
	public interface BufferListener {
		/**
		 * @param message
		 * @return false if message send timed-out or failed
		 */
		boolean onMessage(String message);
		boolean onMessages(List<String> messages);
	}
	
	private final int bufferSize;
	private final Semaphore inputSemaphore;
	private final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
	private AtomicReference<BufferListener> listenerRef = new AtomicReference<BufferListener>();
	
	public TransportBuffer(int bufferSize) {
		this.bufferSize = bufferSize;
		this.inputSemaphore = new Semaphore(bufferSize);
	}

	public void setListener(BufferListener listener) {
		this.listenerRef.set(listener);
	}
	
	public int getBufferSize() {
		return bufferSize;
	}

	public int getAvailableBytes() {
		return bufferSize - inputSemaphore.availablePermits();
	}

	public int getFreeBytes() {
		return inputSemaphore.availablePermits();
	}

	public boolean isEmpty() {
		return queue.isEmpty();
	}

	public void clear() {
		List<String> list = new ArrayList<String>();
		queue.drainTo(list);

		for (String str: list) {
			inputSemaphore.release(str.length());
		}
	}
	
	public List<String> drainMessages() {
		return drainMessages(-1);
	}
	
	public List<String> drainMessages(int count) {
		List<String> list = new ArrayList<String>();
		
		if(count>0){
			queue.drainTo(list, count);
		} else {
			queue.drainTo(list);
		}
		
		for (String str: list) {
			inputSemaphore.release(str.length());
		}
		
		return list;
	}
	
	public String getMessage(long timeout) {
		try {
			String msg = queue.poll(timeout, TimeUnit.MILLISECONDS);
			if (msg != null) {
				inputSemaphore.release(msg.length());
			}
			return msg;
		} catch (InterruptedException e) {
			return null;
		}
	}
	
	public boolean putMessage(String message, long timeout) {
		BufferListener listener = listenerRef.get();
		if (listener != null) {
			try {
				if (queue.size() == 0) {
					return listener.onMessage(message);
				} else {
					ArrayList<String> messages = new ArrayList<String>(queue.size()+1);
					queue.drainTo(messages);
					messages.add(message);
					return listener.onMessages(messages);
				}
			} catch (Throwable t) {
				return false;
			}
		} else {
			try {
				if (!inputSemaphore.tryAcquire(message.length(), timeout, TimeUnit.MILLISECONDS)) {
					return false;
				}
				queue.offer(message);
				return true;
			} catch (InterruptedException e) {
				return false;
			}
		}
	}
}
