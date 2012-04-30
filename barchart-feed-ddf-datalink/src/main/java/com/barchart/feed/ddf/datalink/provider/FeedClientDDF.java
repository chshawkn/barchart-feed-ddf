/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.datalink.provider;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Slf4JLoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.feed.ddf.datalink.api.DDF_FeedClient;
import com.barchart.feed.ddf.datalink.api.DDF_FeedStateListener;
import com.barchart.feed.ddf.datalink.api.DDF_MessageListener;
import com.barchart.feed.ddf.datalink.api.EventPolicy;
import com.barchart.feed.ddf.datalink.enums.DDF_FeedEvent;
import com.barchart.feed.ddf.datalink.enums.DDF_FeedState;
import com.barchart.feed.ddf.message.api.DDF_BaseMessage;
import com.barchart.feed.ddf.message.api.DDF_ControlResponse;
import com.barchart.feed.ddf.message.enums.DDF_MessageType;
import com.barchart.feed.ddf.settings.api.DDF_Server;
import com.barchart.feed.ddf.settings.api.DDF_Settings;
import com.barchart.feed.ddf.settings.enums.DDF_ServerType;
import com.barchart.feed.ddf.settings.provider.DDF_SettingsService;
import com.barchart.feed.ddf.util.FeedDDF;

class FeedClientDDF extends SimpleChannelHandler implements DDF_FeedClient {

	private static final int PORT = 7500;
	private static final int LOGIN_DELAY = 3000;

	/** use slf4j for internal NETTY LoggingHandler facade */
	static {
		final InternalLoggerFactory defaultFactory = new Slf4JLoggerFactory();
		InternalLoggerFactory.setDefaultFactory(defaultFactory);
	}

	private static final Logger log = LoggerFactory
			.getLogger(FeedClientDDF.class);

	/** channel operation time out */
	private static final long TIMEOUT = 2 * 1000;
	private static final String TIMEOUT_OPTION = "connectTimeoutMillis";

	//

	private final Map<DDF_FeedEvent, EventPolicy> eventPolicy = Collections
			.synchronizedMap(new EnumMap<DDF_FeedEvent, EventPolicy>(
					DDF_FeedEvent.class));

	//

	private final LoginHandler loginHandler = new LoginHandler();

	//

	private volatile DDF_MessageListener msgListener = null;

	private volatile DDF_FeedStateListener stateListener = null;

	//

	private final String username;
	private final String password;
	private final DDF_ServerType serverType;
	private final Executor runner;

	FeedClientDDF(final String username, final String password,
			final DDF_ServerType serverType, final Executor executor) {

		this.username = username;
		this.password = password;
		this.serverType = serverType;
		this.runner = executor;

		final ChannelFactory channelFactory = new NioClientSocketChannelFactory(
				runner, runner);

		boot = new ClientBootstrap(channelFactory);

		final ChannelPipelineFactory pipelineFactory = new PipelineFactoryDDF(
				this);

		boot.setPipelineFactory(pipelineFactory);

		boot.setOption(TIMEOUT_OPTION, TIMEOUT);

		// Initialize event policy with event policies that do nothing.
		for (final DDF_FeedEvent event : DDF_FeedEvent.values()) {
			eventPolicy.put(event, new EventPolicy() {

				@Override
				public void newEvent() {
					// Do nothing
				}
			});
		}

		// Add DefaultReloginPolicy to selected events
		eventPolicy
				.put(DDF_FeedEvent.LOGIN_FAILURE, new DefaultReloginPolicy());
		eventPolicy.put(DDF_FeedEvent.LINK_DISCONNECT,
				new DefaultReloginPolicy());
		eventPolicy.put(DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE,
				new DefaultReloginPolicy());
		eventPolicy.put(DDF_FeedEvent.CHANNEL_CONNECT_FAILURE,
				new DefaultReloginPolicy());
		eventPolicy.put(DDF_FeedEvent.CHANNEL_CONNECT_TIMEOUT,
				new DefaultReloginPolicy());

	}

	private class DefaultReloginPolicy implements EventPolicy {

		@Override
		public void newEvent() {
			loginHandler.loginWithDelay(LOGIN_DELAY);
		}

	}

	private final BlockingQueue<DDF_FeedEvent> eventQueue = new LinkedBlockingQueue<DDF_FeedEvent>();

	private final BlockingQueue<DDF_BaseMessage> messageQueue = new LinkedBlockingQueue<DDF_BaseMessage>();

	private final BlockingQueue<CharSequence> commandQueue = new LinkedBlockingQueue<CharSequence>();

	private final RunnerDDF eventTask = new RunnerDDF() {
		@Override
		protected void runCore() {
			while (true) {
				try {
					final DDF_FeedEvent event = eventQueue.take();

					if (DDF_FeedEvent.isError(event)) {
						log.debug("Setting feed state to logged out");
						stateListener.stateUpdate(DDF_FeedState.LOGGED_OUT);
					}

					log.debug("Enacting policy for :{}", event.name());
					eventPolicy.get(event).newEvent();
				} catch (final InterruptedException e) {
					log.trace("terminated");
					return;
				} catch (final Throwable e) {
					log.error("event delivery failed", e);
				}
			}
		}
	};

	private final RunnerDDF messageTask = new RunnerDDF() {
		@Override
		protected void runCore() {
			while (true) {
				try {
					final DDF_BaseMessage message = messageQueue.take();
					if (msgListener != null) {
						msgListener.handleMessage(message);
					}
				} catch (final InterruptedException e) {
					log.trace("terminated");
					return;
				} catch (final Throwable e) {
					log.error("message delivery failed", e);
				}
			}
		}
	};

	private final RunnerDDF commandTask = new RunnerDDF() {
		@Override
		protected void runCore() {
			while (true) {
				try {
					final CharSequence command = commandQueue.take();
					if (isConnected()) {
						send(command);
					}
				} catch (final InterruptedException e) {
					log.trace("terminated");
					return;
				} catch (final Throwable e) {
					log.error("message delivery failed", e);
				}
			}
		}
	};

	@Override
	public void setPolicy(final DDF_FeedEvent event, final EventPolicy policy) {
		log.debug("Setting policy for :{}", event.name());
		eventPolicy.put(event, policy);
	}

	private void postEvent(final DDF_FeedEvent event) {
		try {
			eventQueue.put(event);
		} catch (final InterruptedException e) {
			log.trace("terminated");
		}
	}

	private void postMessage(final DDF_BaseMessage message) {
		try {
			messageQueue.put(message);
		} catch (final InterruptedException e) {
			log.trace("terminated");
		}
	}

	//

	private final ClientBootstrap boot;

	private Channel channel;

	private void initialize() {

		runner.execute(eventTask);
		runner.execute(messageTask);
		runner.execute(commandTask);

	}

	private void terminate() {

		eventTask.interrupt();
		messageTask.interrupt();
		commandTask.interrupt();

		eventQueue.clear();
		messageQueue.clear();
		commandQueue.clear();

		if (channel != null) {
			channel.close();
			channel = null;
		}

	}

	/**
	 * Can post to the FeedEventHandler the following events:
	 * <p>
	 * CHANNEL_CONNECT_TIMEOUT {@link DDF_FeedEvent.CHANNEL_CONNECT_TIMEOUT}
	 * <p>
	 * CHANNEL_CONNECT_FAILURE {@link DDF_FeedEvent.CHANNEL_CONNECT_FAILURE}
	 * <p>
	 * SETTINGS_RETRIEVAL_FAILURE
	 * {@link DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE}
	 * <p>
	 * LOGIN_FAILURE {@link DDF_FeedEvent.LOGIN_FAILURE}
	 * <p>
	 * LOGIN_SUCCESS {@link DDF_FeedEvent.LOGIN_SUCCESS}
	 */
	@Override
	public synchronized void login() {

		log.debug("Public login called");
		loginHandler.enableLogins();
		loginHandler.login();

	}

	// Explain
	private synchronized DDF_FeedEvent loginSomething() {

		log.debug("LoginSomething called");

		terminate();

		initialize();

		DDF_Settings settings = null;
		try {
			settings = DDF_SettingsService.newSettings(username, password);
			if (!settings.isValidLogin()) {
				log.warn("Posting SETTINGS_RETRIEVAL_FAILURE");
				return DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE;
			}
		} catch (final Exception e) {
			log.warn("Posting SETTINGS_RETRIEVAL_FAILURE");
			return DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE;
		}

		final DDF_Server server = settings.getServer(serverType);
		final String primary = server.getPrimary();
		final String secondary = server.getSecondary();

		final DDF_FeedEvent eventOne = login(primary, PORT);

		if (eventOne == DDF_FeedEvent.LOGIN_SENT) {
			log.debug("Posting LOGIN_SUCCESS for primary server");
			return DDF_FeedEvent.LOGIN_SENT;
		}

		final DDF_FeedEvent eventTwo = login(secondary, PORT);

		if (eventTwo == DDF_FeedEvent.LOGIN_SENT) {
			log.debug("Posting LOGIN_SUCCESS for secondary server");
			return DDF_FeedEvent.LOGIN_SENT;
		}

		// For simplicity, we only return the error message from the primary
		// server in the event both logins fail.
		log.warn("Posting {}", eventOne.name());
		return eventOne;

	}

	// Explain
	private DDF_FeedEvent login(final String host, final int port) {

		final InetSocketAddress address = new InetSocketAddress(host, port);

		ChannelFuture futureConnect = null;

		try {
			futureConnect = boot.connect(address);
		} catch (final Exception e) {
			log.warn("channel connect unsuccessful; {}:{} ", host, port);
			return DDF_FeedEvent.CHANNEL_CONNECT_FAILURE;
		}
		channel = futureConnect.getChannel();

		futureConnect.awaitUninterruptibly();

		if (!futureConnect.isDone()) {
			log.warn("channel connect timeout; {}:{} ", host, port);
			return DDF_FeedEvent.CHANNEL_CONNECT_TIMEOUT;
		}

		if (!futureConnect.isSuccess()) {
			log.warn("channel connect unsuccessful; {}:{} ", host, port);
			return DDF_FeedEvent.CHANNEL_CONNECT_FAILURE;
		}

		final boolean isLoginSent = true && //
				send(FeedDDF.tcpLogin(username, password)) && //
				send(FeedDDF.tcpVersion(FeedDDF.VERSION_3)) && //
				send(FeedDDF.tcpGo(FeedDDF.SYMBOL_TIMESTAMP)) && //
				true;

		if (!isLoginSent) {
			log.warn("login startup timeout; {}:{} ", host, port);
			return DDF_FeedEvent.LOGIN_FAILURE;
		}

		return DDF_FeedEvent.LOGIN_SENT;

	}

	@Override
	public synchronized void logout() {

		loginHandler.disableLogins();

		postEvent(DDF_FeedEvent.LOGOUT);

		send(FeedDDF.tcpLogout());

		terminate();

	}

	private boolean isConnected() {
		if (channel == null) {
			return false;
		}
		return channel.isConnected();
	}

	@Override
	public boolean send(final CharSequence command) {

		if (command == null) {
			log.error("command == null", new Exception());
			return false;
		}

		if (!isConnected()) {
			log.error("!isConnected()");
			return false;
		}

		final ChannelFuture futureWrite = channel.write(command);

		final boolean isWritten = futureWrite.awaitUninterruptibly(TIMEOUT);

		return isWritten;

	}

	@Override
	public boolean post(final CharSequence command) {

		if (command == null) {
			log.error("command == null", new Exception());
			return false;
		}

		if (!isConnected()) {
			return false;
		}

		try {
			commandQueue.put(command);
		} catch (final InterruptedException e) {
			log.trace("interrupted");
		}

		return true;

	}

	@Override
	public synchronized void bindMessageListener(
			final DDF_MessageListener handler) {
		this.msgListener = handler;
	}

	@Override
	public synchronized void bindStateListener(
			final DDF_FeedStateListener stateListener) {
		this.stateListener = stateListener;
	}

	@Override
	public void channelConnected(final ChannelHandlerContext ctx,
			final ChannelStateEvent e) throws Exception {

		log.debug("Posting LINK_CONNECT");
		postEvent(DDF_FeedEvent.LINK_CONNECT);

		ctx.sendUpstream(e);

	}

	@Override
	public void channelDisconnected(final ChannelHandlerContext ctx,
			final ChannelStateEvent e) throws Exception {

		log.warn("Posting LINK_DISCONNECT");
		postEvent(DDF_FeedEvent.LINK_DISCONNECT);

		ctx.sendUpstream(e);

	}

	@Override
	public void exceptionCaught(final ChannelHandlerContext ctx,
			final ExceptionEvent e) throws Exception {

		log.warn("SimpleChannelHandler caught exception");
		postEvent(DDF_FeedEvent.CHANNEL_CONNECT_FAILURE);

		ctx.sendUpstream(e);

	}

	private void doMarket(final DDF_BaseMessage message) {

		postMessage(message);

	}

	private void doResponse(final DDF_BaseMessage message) {

		postMessage(message);

		final DDF_MessageType type = message.getMessageType();
		final DDF_ControlResponse control = (DDF_ControlResponse) message;
		final String comment = control.getComment().toString();

		switch (type) {
		case TCP_ACCEPT:
			// Note: This is the only place a login sucess is set
			if (comment.contains(FeedDDF.RESPONSE_LOGIN_SUCCESS)) {
				postEvent(DDF_FeedEvent.LOGIN_SUCCESS);
				log.debug("Setting feed state to logged in");
				stateListener.stateUpdate(DDF_FeedState.LOGGED_IN);
			}
			break;
		case TCP_REJECT:
			if (comment.contains(FeedDDF.RESPONSE_LOGIN_FAILURE)) {
				postEvent(DDF_FeedEvent.LOGIN_FAILURE);
				stateListener.stateUpdate(DDF_FeedState.LOGGED_OUT);
			}
			break;
		case TCP_COMMAND:
			if (comment.contains(FeedDDF.RESPONSE_SESSION_LOCKOUT)) {
				postEvent(DDF_FeedEvent.SESSION_LOCKOUT);
			}
			break;
		default:
			break;
		}

	}

	private void doTimestamp(final DDF_BaseMessage message) {

		postMessage(message);

		postEvent(DDF_FeedEvent.HEART_BEAT);

	}

	@Override
	public void messageReceived(final ChannelHandlerContext context,
			final MessageEvent eventIn) throws Exception {

		final Object messageIn = eventIn.getMessage();

		if (!(messageIn instanceof DDF_BaseMessage)) {
			context.sendUpstream(eventIn);
			return;
		}

		final DDF_BaseMessage message = (DDF_BaseMessage) messageIn;
		final DDF_MessageType type = message.getMessageType();

		if (type.isMarketMessage) {
			doMarket(message);
			return;
		}

		if (type.isControlTimestamp) {
			doTimestamp(message);
			return;
		}

		if (type.isControlResponse) {
			doResponse(message);
			return;
		}

		log.debug("unknown message : {}", message);

	}

	private class LoginHandler {

		private Thread loginThread = null;
		private boolean enabled = true;

		void enableLogins() {
			enabled = true;
		}

		void disableLogins() {
			enabled = false;
		}

		void login() {

			if (enabled) {
				if (loginThread == null || !loginThread.isAlive()) {
					loginThread = new Thread(new Runnable() {

						@Override
						public void run() {
							log.debug("From LoginHandler.login()");
							postEvent(loginSomething());
						}
					});
					loginThread.start();

					log.debug("Setting feed state to attempting login");
					stateListener.stateUpdate(DDF_FeedState.ATTEMPTING_LOGIN);
				}
			}
		}

		void loginWithDelay(final int delay) {

			if (enabled) {
				if (loginThread == null || !loginThread.isAlive()) {

					loginThread = new Thread(new Runnable() {

						@Override
						public void run() {
							try {
								Thread.sleep(delay);
							} catch (final InterruptedException e) {
								e.printStackTrace();
							}
							log.debug("From LoginHandler.loginWithDelay");
							postEvent(loginSomething());
						}
					});
					loginThread.start();

					log.debug("Setting feed state to attempting login");
					stateListener.stateUpdate(DDF_FeedState.ATTEMPTING_LOGIN);
				}
			}
		}

	}

}
