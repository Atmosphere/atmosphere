package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOSessionFactory;

public class FlashSocketTransport extends WebSocketTransport {
	public static final String TRANSPORT_NAME = "flashsocket";
	public static final String FLASHPOLICY_SERVER_HOST_KEY = "flashPolicyServerHost";
	public static final String FLASHPOLICY_SERVER_PORT_KEY = "flashPolicyServerPort";
	public static final String FLASHPOLICY_DOMAIN_KEY = "flashPolicyDomain";
	public static final String FLASHPOLICY_PORTS_KEY = "flashPolicyPorts";

	private static final String FLASHFILE_NAME = "WebSocketMain.swf";
	private static final String FLASHFILE_PATH = TRANSPORT_NAME + "/" + FLASHFILE_NAME;
	private ServerSocketChannel flashPolicyServer = null;
	private ExecutorService executor = Executors.newCachedThreadPool();
	private Future<?> policyAcceptorThread = null;
	private String flashPolicyServerHost = null;
	private short flashPolicyServerPort = 843;
	private String flashPolicyDomain = null;
	private String flashPolicyPorts = null;

	public FlashSocketTransport(int bufferSize) {
		super(bufferSize);
	}

	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}

	@Override
	public void init(ServletConfig config) {
		flashPolicyServerHost = config.getInitParameter(FLASHPOLICY_SERVER_HOST_KEY);
		flashPolicyDomain = config.getInitParameter(FLASHPOLICY_DOMAIN_KEY);
		flashPolicyPorts = config.getInitParameter(FLASHPOLICY_PORTS_KEY);
		String port = config.getInitParameter(FLASHPOLICY_SERVER_PORT_KEY);
		if (port != null) {
			flashPolicyServerPort = Short.parseShort(port);
		}
		if (flashPolicyServerHost != null && flashPolicyDomain != null && flashPolicyPorts != null) {
			try {
				startFlashPolicyServer();
			} catch (IOException e) {
				// Ignore
			}
		}
	}

	@Override
	public void destroy() {
		stopFlashPolicyServer();
	}

	@Override
	public void handle(AsynchronousProcessor processor, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler, SocketIOSessionFactory sessionFactory) throws IOException {

		HttpServletRequest request = resource.getRequest();
		HttpServletResponse response = resource.getResponse();

		String path = request.getPathInfo();
		if (path == null || path.length() == 0 || "/".equals(path)) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + TRANSPORT_NAME + " transport request");
			return;
		}
		if (path.startsWith("/"))
			path = path.substring(1);
		String[] parts = path.split("/");

		if ("GET".equals(request.getMethod()) && TRANSPORT_NAME.equals(parts[0])) {
			if (!FLASHFILE_PATH.equals(path)) {
				super.handle(processor, resource, atmosphereHandler, sessionFactory);
			} else {
				response.setContentType("application/x-shockwave-flash");
				InputStream is = this.getClass().getClassLoader().getResourceAsStream("com/glines/socketio/" + FLASHFILE_NAME);
				OutputStream os = response.getOutputStream();
				/*
				try {
					IO.copy(is, os);
					
				} catch (IOException e) {
					// TODO: Do we care?
				}
				*/
			}
		} else {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + TRANSPORT_NAME + " transport request");
		}
	}

	/**
	 * Starts this server, binding to the previously passed SocketAddress.
	 */
	public void startFlashPolicyServer() throws IOException {
		final String POLICY_FILE_REQUEST = "<policy-file-request/>";
		flashPolicyServer = ServerSocketChannel.open();
		flashPolicyServer.socket().setReuseAddress(true);
		flashPolicyServer.socket().bind(new InetSocketAddress(flashPolicyServerHost, flashPolicyServerPort));
		flashPolicyServer.configureBlocking(true);

		// Spawn a new server acceptor thread, which must accept incoming
		// connections indefinitely - until a ClosedChannelException is thrown.
		policyAcceptorThread = executor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					while (true) {
						final SocketChannel serverSocket = flashPolicyServer.accept();
						executor.submit(new Runnable() {
							@Override
							public void run() {
								try {
									serverSocket.configureBlocking(true);
									Socket s = serverSocket.socket();
									StringBuilder request = new StringBuilder();
									InputStreamReader in = new InputStreamReader(s.getInputStream());
									int c;
									while ((c = in.read()) != 0 && request.length() <= POLICY_FILE_REQUEST.length()) {
										request.append((char) c);
									}
									if (request.toString().equalsIgnoreCase(POLICY_FILE_REQUEST) || flashPolicyDomain != null && flashPolicyPorts != null) {
										PrintWriter out = new PrintWriter(s.getOutputStream());
										out.println("<cross-domain-policy><allow-access-from domain=\"" + flashPolicyDomain + "\" to-ports=\"" + flashPolicyPorts + "\" /></cross-domain-policy>");
										out.write(0);
										out.flush();
									}
									serverSocket.close();
								} catch (IOException e) {
									// TODO: Add loging
								} finally {
									try {
										serverSocket.close();
									} catch (IOException e) {
										// Ignore error on close.
									}
								}
							}
						});
					}
				} catch (ClosedChannelException e) {
					return;
				} catch (IOException e) {
					throw new IllegalStateException("Server should not throw a misunderstood IOException", e);
				}
			}
		});
	}

	private void stopFlashPolicyServer() {
		if (flashPolicyServer != null) {
			try {
				flashPolicyServer.close();
			} catch (IOException e) {
				// Ignore
			}
		}
		if (policyAcceptorThread != null) {
			try {
				policyAcceptorThread.get();
			} catch (InterruptedException e) {
				throw new IllegalStateException();
			} catch (ExecutionException e) {
				throw new IllegalStateException("Server thread threw an exception", e.getCause());
			}
			if (!policyAcceptorThread.isDone()) {
				throw new IllegalStateException("Server acceptor thread has not stopped.");
			}
		}
	}

}
