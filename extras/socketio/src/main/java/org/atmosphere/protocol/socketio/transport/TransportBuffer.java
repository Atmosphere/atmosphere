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
