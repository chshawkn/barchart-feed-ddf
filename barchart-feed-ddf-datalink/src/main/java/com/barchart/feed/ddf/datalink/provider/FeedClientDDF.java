/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.datalink.provider;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Slf4JLoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.feed.client.api.FeedStateListener;
import com.barchart.feed.client.enums.FeedState;
import com.barchart.feed.ddf.datalink.api.CommandFuture;
import com.barchart.feed.ddf.datalink.api.DDF_FeedClient;
import com.barchart.feed.ddf.datalink.api.DDF_MessageListener;
import com.barchart.feed.ddf.datalink.api.DDF_SocksProxy;
import com.barchart.feed.ddf.datalink.api.DummyFuture;
import com.barchart.feed.ddf.datalink.api.EventPolicy;
import com.barchart.feed.ddf.datalink.api.Subscription;
import com.barchart.feed.ddf.datalink.enums.DDF_FeedEvent;
import com.barchart.feed.ddf.message.api.DDF_BaseMessage;
import com.barchart.feed.ddf.settings.api.DDF_Server;
import com.barchart.feed.ddf.settings.api.DDF_Settings;
import com.barchart.feed.ddf.settings.enums.DDF_ServerType;
import com.barchart.feed.ddf.settings.provider.DDF_SettingsService;
import com.barchart.feed.ddf.util.FeedDDF;

class FeedClientDDF implements DDF_FeedClient {

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
	private static final TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;

	private static final long HEARTBEAT_TIMEOUT = 10 * 1000;

	//

	private final Map<DDF_FeedEvent, EventPolicy> eventPolicy = new ConcurrentHashMap<DDF_FeedEvent, EventPolicy>();

	private final Set<Subscription> subscriptions = new CopyOnWriteArraySet<Subscription>();

	//

	private final LoginHandler loginHandler = new LoginHandler();
	private volatile boolean loggingIn = false;
	//

	private final BlockingQueue<DDF_FeedEvent> eventQueue = new LinkedBlockingQueue<DDF_FeedEvent>();

	private final BlockingQueue<DDF_BaseMessage> messageQueue = new LinkedBlockingQueue<DDF_BaseMessage>();

	private final AtomicLong lastHeartbeat = new AtomicLong(0);

	//

	private volatile DDF_MessageListener msgListener = null;

	private final CopyOnWriteArrayList<FeedStateListener> feedListeners = new CopyOnWriteArrayList<FeedStateListener>();

	//

	private ClientBootstrap boot;

	private Channel channel;

	//

	private String username;
	private String password;
	private DDF_ServerType serverType = DDF_ServerType.STREAM;
	private Executor executor;

	// SOCKS5

	private DDF_SocksProxy proxySettings = null;
	private final BlockingQueue<Boolean> socksConnectResult = new LinkedBlockingQueue<Boolean>();

	//

	FeedClientDDF(final String username, final String password,
			final Executor executor) {

		startup(username, password, executor, null);

	}

	public FeedClientDDF(String username, String password, Executor executor,
			DDF_SocksProxy proxySettings) {

		startup(username, password, executor, proxySettings);

	}

	private void startup(final String username, final String password,
			final Executor exec, final DDF_SocksProxy proxy) {

		this.username = username;
		this.password = password;
		this.executor = exec;

		this.proxySettings = proxy;

		final ChannelFactory channelFactory = new NioClientSocketChannelFactory(
				executor, executor);

		boot = new ClientBootstrap(channelFactory);

		if (proxySettings == null) {

			/*
			 * The vector for data leaving the netty channel and entering the
			 * business application logic.
			 */
			final SimpleChannelHandler ddfHandler = new ChannelHandlerDDF(
					eventQueue, messageQueue);

			final ChannelPipelineFactory pipelineFactory = new PipelineFactoryDDF(
					ddfHandler);

			boot.setPipelineFactory(pipelineFactory);

		} else {

			final ChannelPipelineFactory socksPipelineFactory = new PipelineFactorySocks(
					executor, this, proxy);

			boot.setPipelineFactory(socksPipelineFactory);
			boot.setOption("child.tcpNoDelay", true);
			boot.setOption("child.keepAlive", true);
			boot.setOption("child.reuseAddress", true);
			boot.setOption("readWriteFair", true);

		}

		boot.setOption(TIMEOUT_OPTION, TIMEOUT);

		/* Initialize event policy with event policies that do nothing. */
		for (final DDF_FeedEvent event : DDF_FeedEvent.values()) {
			eventPolicy.put(event, new EventPolicy() {

				@Override
				public void newEvent() {
					/* Do nothing */
				}
			});
		}

		/* Add DefaultReloginPolicy to selected events */
		eventPolicy.put(DDF_FeedEvent.LOGIN_FAILURE, reconnectionPolicy);

		eventPolicy.put(DDF_FeedEvent.LINK_DISCONNECT, reconnectionPolicy);
		eventPolicy.put(DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE,
				reconnectionPolicy);

		eventPolicy.put(DDF_FeedEvent.CHANNEL_CONNECT_FAILURE,
				reconnectionPolicy);
		eventPolicy.put(DDF_FeedEvent.CHANNEL_CONNECT_TIMEOUT,
				reconnectionPolicy);
		eventPolicy.put(DDF_FeedEvent.LINK_CONNECT_PROXY_TIMEOUT,
				reconnectionPolicy);

		/* Add SubscribeAfterLogin to LOGIN_SUCCESS */
		eventPolicy.put(DDF_FeedEvent.LOGIN_SUCCESS, new SubscribeAfterLogin());

		/* Add HeartbeatPolicy to HEART_BEAT */
		eventPolicy.put(DDF_FeedEvent.HEART_BEAT, new HeartbeatPolicy());

		/* Start heart beat listener */
		executor.execute(new HeartbeatListener());

		executor.execute(eventTask);
		executor.execute(messageTask);
	}

	private final DefaultReloginPolicy reconnectionPolicy = new DefaultReloginPolicy();

	private boolean loginProxy(String username, String password,
			DDF_Server feedServers) {

		terminate();

		initialize();

		// do socks connection

		log.error("connect to proxy - address {} port {}",
				proxySettings.getProxyAddress(), proxySettings.getProxyPort());

		final InetSocketAddress address = new InetSocketAddress(
				proxySettings.getProxyAddress(), proxySettings.getProxyPort());

		final ChannelFuture futureConnect = boot.connect(address);

		channel = futureConnect.getChannel();

		final boolean isGoodConnect = futureConnect
				.awaitUninterruptibly(TIMEOUT);

		if (!isGoodConnect) {
			log.error("proxy connect error {}", futureConnect.getCause());
			log.error("proxy; {}:{} ", proxySettings.getProxyAddress(),
					proxySettings.getProxyPort());

			postEvent(DDF_FeedEvent.LINK_CONNECT_PROXY_TIMEOUT);

			channel.close();
			return false;
		}

		log.debug("server = {}", feedServers.getPrimary());

		// set the ddf servers

		proxySettings.setFeedServer(feedServers);

		// block until we get proxy_connect_command result

		Boolean proxyResult = false;

		try {
			proxyResult = socksConnectResult.take();
		} catch (InterruptedException e) {
		}

		if (proxyResult == false) {

			log.error("Socks connect error");
			// postEvent(DDF_FeedEvent.LINK_CONNECT_PROXY_TIMEOUT);

			return false;
		}

		/* Send login command to JERQ */
		DDF_FeedEvent writeEvent = blockingWrite(FeedDDF.tcpLogin(username,
				password));

		if (writeEvent == DDF_FeedEvent.COMMAND_WRITE_FAILURE) {
			log.error("error sending login command to jerq");
			return false;
		}

		/* Send VERSION 3 command to JERQ */
		writeEvent = blockingWrite(FeedDDF.tcpVersion(FeedDDF.VERSION_3));

		if (writeEvent == DDF_FeedEvent.COMMAND_WRITE_FAILURE) {
			log.error("error sending VERSION 3 command to jerq");
			return false;
		}

		/* Send timestamp command to JERQ */
		writeEvent = blockingWrite(FeedDDF.tcpGo(FeedDDF.SYMBOL_TIMESTAMP));

		if (writeEvent == DDF_FeedEvent.COMMAND_WRITE_FAILURE) {
			log.error("error sending login GO TIMESTAMP to jerq");
			return false;
		}

		// all is good

		return true;

	}

	/*
	 * This policy ensures that all subscribed instruments are requested from
	 * JERQ upon login or relogin after a dicsonnect
	 */
	private class SubscribeAfterLogin implements EventPolicy {

		@Override
		public void newEvent() {
			if (subscriptions.size() > 0) {
				log.debug("Requesting current subscriptions");
				subscribe(subscriptions);
			}
		}
	}

	/*
	 * This policy pauses a runner thread for a specified time interval and then
	 * attempts to log in.
	 */
	private class DefaultReloginPolicy implements EventPolicy {

		@Override
		public void newEvent() {
			synchronized (loginHandler) {
				loginHandler.login(LOGIN_DELAY);
			}
		}
	}

	/*
	 * This policy updates the lastHeartbeat to the current local clock on every
	 * heart beat event received.
	 */
	private class HeartbeatPolicy implements EventPolicy {
		@Override
		public void newEvent() {
			lastHeartbeat.set(System.currentTimeMillis());
		}
	}

	private final RunnerDDF eventTask = new RunnerDDF() {

		@Override
		protected void runCore() {

			Thread.currentThread().setName("# EVENT TASK");

			while (true) {

				try {

					final DDF_FeedEvent event = eventQueue.take();

					if (DDF_FeedEvent.isConnectionError(event)) {

						log.debug("Setting feed state to logged out");
						updateFeedStateListeners(FeedState.LOGGED_OUT);

					} else if (event == DDF_FeedEvent.LOGIN_SUCCESS) {

						log.debug("Login success, feed state updated");
						updateFeedStateListeners(FeedState.LOGGED_IN);

					} else if (event == DDF_FeedEvent.LOGOUT) {

						log.debug("Setting feed state to logged out");
						updateFeedStateListeners(FeedState.LOGGED_OUT);

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

			Thread.currentThread().setName("# DDF MessageTask");

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

	@Override
	public void setPolicy(final DDF_FeedEvent event, final EventPolicy policy) {
		log.debug("Setting policy for :{}", event.name());
		eventPolicy.put(event, policy);
	}

	//

	void postEvent(final DDF_FeedEvent event) {
		try {
			eventQueue.put(event);
		} catch (final InterruptedException e) {
			log.trace("terminated");
		}
	}

	/**
	 * the calls to initialize() and terminate were causing the threading issue,
	 * as the runnables were never getting shutdown...
	 */

	private void initialize() {

		// do this once

		// executor.execute(eventTask);
		// executor.execute(messageTask);

	}

	private void terminate() {

		// did not work, maybe because the while(true)

		// eventTask.interrupt();
		// messageTask.interrupt();

		eventQueue.clear();
		messageQueue.clear();

		if (channel != null) {
			channel.close();
			channel = null;
		}

	}

	/*
	 * Can post to the FeedEventHandler the following events: <p>
	 * CHANNEL_CONNECT_TIMEOUT {@link DDF_FeedEvent.CHANNEL_CONNECT_TIMEOUT} <p>
	 * CHANNEL_CONNECT_FAILURE {@link DDF_FeedEvent.CHANNEL_CONNECT_FAILURE} <p>
	 * SETTINGS_RETRIEVAL_FAILURE {@link
	 * DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE} <p> LOGIN_FAILURE {@link
	 * DDF_FeedEvent.LOGIN_FAILURE} <p> LOGIN_SUCCESS {@link
	 * DDF_FeedEvent.LOGIN_SUCCESS}
	 */
	@Override
	public synchronized void startup() {

		log.debug("Public login called");
		loginHandler.enableLogins();
		loginHandler.login(0);

	}

	private DDF_FeedEvent blockingWrite(final CharSequence message) {
		final ChannelFuture futureWrite = channel.write(message);

		futureWrite.awaitUninterruptibly(TIMEOUT, TIME_UNIT);

		if (futureWrite.isSuccess()) {
			return DDF_FeedEvent.COMMAND_WRITE_SUCCESS;
		} else {
			return DDF_FeedEvent.COMMAND_WRITE_FAILURE;
		}
	}

	@Override
	public synchronized void shutdown() {

		loginHandler.disableLogins();

		/* Interrupts login thread if login is active */
		loginHandler.interruptLogin();

		/* Clear subscriptions, Jerq will stop sending data when we disconnect */
		subscriptions.clear();

		postEvent(DDF_FeedEvent.LOGOUT);

		// Do we need to specifically tell JERQ we're logging out?
		// blockingWrite(FeedDDF.tcpLogout());

		terminate();

	}

	private boolean isConnected() {
		if (channel == null) {
			return false;
		}
		return channel.isConnected();
	}

	private void disconnect() {
		channel.disconnect();
	}

	/*
	 * Adds a COMMAND_WRITE_ERROR to the event queue if an attempt to write to
	 * the channel fails.
	 */
	private class CommandFailureListener implements ChannelFutureListener {

		@Override
		public void operationComplete(final ChannelFuture future)
				throws Exception {
			if (!future.isSuccess()) {
				postEvent(DDF_FeedEvent.COMMAND_WRITE_FAILURE);
			}
		}

	}

	/* Asynchronous write to the channel, future returns true on success */
	private Future<Boolean> writeAsync(final String message) {
		log.debug("Attempting to send reqeust to JERQ : {}", message);
		final ChannelFuture future = channel.write(message + "\n");
		future.addListener(new CommandFailureListener());
		return new CommandFuture(future);
	}

	@Override
	public Future<Boolean> subscribe(final Set<Subscription> subs) {

		if (subs == null) {
			log.error("Null subscribes request recieved");
			return null;
		}

		if (!isConnected()) {
			return new DummyFuture();
		}

		/*
		 * Creates a single JERQ command from the set, subscriptions are added
		 * indivually.
		 */
		final StringBuffer sb = new StringBuffer();
		sb.append("GO ");
		for (final Subscription sub : subs) {

			if (sub != null) {
				subscriptions.add(sub);
				sb.append(sub.subscribe() + ",");
			}
		}
		return writeAsync(sb.toString());
	}

	@Override
	public Future<Boolean> subscribe(final Subscription sub) {

		if (sub == null) {
			log.error("Null subscribe request recieved");
			return null;
		}

		/*
		 * Add subscription to set. This will overwrite an existing subscription
		 * for the same instrument.
		 */
		subscriptions.add(sub);

		if (!isConnected()) {
			return new DummyFuture();
		}

		/* Request subscription from JERQ and return the future */
		return writeAsync("GO " + sub.subscribe());
	}

	@Override
	public Future<Boolean> unsubscribe(final Set<Subscription> subs) {

		if (subs == null) {
			log.error("Null subscribes request recieved");
			return null;
		}

		if (!isConnected()) {
			return new DummyFuture();
		}

		/*
		 * Creates a single JERQ command from the set. Subscriptions are removed
		 * individually.
		 */
		final StringBuffer sb = new StringBuffer();
		sb.append("STOP ");
		for (final Subscription sub : subs) {

			if (sub != null) {
				subscriptions.remove(sub);
				sb.append(sub.unsubscribe() + ",");
			}
		}
		return writeAsync(sb.toString());
	}

	@Override
	public Future<Boolean> unsubscribe(final Subscription sub) {

		if (sub == null) {
			log.error("Null subscribe request recieved");
			return null;
		}

		/* Removes subscription from set */
		subscriptions.remove(sub);

		if (!isConnected()) {
			return new DummyFuture();
		}

		/* Request subscription from JERQ and return the future */
		return writeAsync("STOP " + sub.unsubscribe());
	}

	@Override
	public synchronized void bindMessageListener(
			final DDF_MessageListener handler) {
		this.msgListener = handler;
	}

	@Override
	public synchronized void bindStateListener(
			final FeedStateListener stateListener) {
		feedListeners.add(stateListener);
	}

	private volatile Thread loginThread = null;

	int i = 0;

	private class LoginHandler {

		private boolean enabled = true;

		void enableLogins() {
			enabled = true;
		}

		void disableLogins() {
			enabled = false;
		}

		boolean isLoginActive() {
			return loginThread != null && loginThread.isAlive();
		}

		void interruptLogin() {
			if (isLoginActive()) {
				loginThread.interrupt();
			}
		}

		synchronized void login(final int delay) {

			log.warn("login enabled {} - logginIn - {} isLoginActive = "
					+ isLoginActive(), enabled, loggingIn
					+ " reconnect attempt count = " + i++);

			if (proxySettings != null) {

				startUpProxy();

				return;
			}

			if (enabled && loggingIn == false) {

				log.warn("logging in regular, starting new thread..");

				loggingIn = true;

				loginThread = new Thread(new LoginRunnable(delay),
						"# DDF Login " + i++);

				executor.execute(loginThread);

				log.warn("Setting feed state to attempting login");

				updateFeedStateListeners(FeedState.ATTEMPTING_LOGIN);

			}
		}

	}

	private void updateFeedStateListeners(final FeedState state) {
		for (final FeedStateListener listener : feedListeners) {
			listener.stateUpdate(state);
		}
	}

	/* Runnable which handles connection, login, and initializaion */
	class LoginRunnable implements Runnable {

		private final int delay;

		public LoginRunnable(final int delay) {
			this.delay = delay;
		}

		@Override
		public void run() {

			try {
				Thread.sleep(delay);
			} catch (final InterruptedException e1) {
				e1.printStackTrace();
			}

			log.warn("making connection");

			terminate();

			initialize();

			/* Attempt to get current data server settings */
			DDF_Settings settings = null;
			try {
				settings = DDF_SettingsService.newSettings(username, password);
				if (!settings.isValidLogin()) {
					log.warn("Posting SETTINGS_RETRIEVAL_FAILURE");
					postEvent(DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE);

					loggingIn = false;

					return;
				}
			} catch (final Exception e) {
				log.warn("Posting SETTINGS_RETRIEVAL_FAILURE");
				postEvent(DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE);

				loggingIn = false;

				return;
			}

			final DDF_Server server = settings.getServer(serverType);
			final String primary = server.getPrimary();
			final String secondary = server.getSecondary();

			/* Attempt to connect and login to primary server */
			final DDF_FeedEvent eventOne = login(primary, PORT);

			if (eventOne == DDF_FeedEvent.LOGIN_SENT) {
				log.debug("Posting LOGIN_SENT for primary server");
				postEvent(DDF_FeedEvent.LOGIN_SENT);

				loggingIn = false;

				return;
			}

			/* Attempt to connect and login to secondary server */
			final DDF_FeedEvent eventTwo = login(secondary, PORT);

			if (eventTwo == DDF_FeedEvent.LOGIN_SENT) {
				log.debug("Posting LOGIN_SENT for secondary server");
				postEvent(DDF_FeedEvent.LOGIN_SENT);

				loggingIn = false;

				return;
			}

			/*
			 * For simplicity, we only return the error message from the primary
			 * server in the event both logins fail.
			 */
			log.warn("Posting {}", eventOne.name());
			postEvent(eventOne);

			loggingIn = false;

			return;
		}

		/* Handles the login for an individual server */
		private DDF_FeedEvent login(final String host, final int port) {
			final InetSocketAddress address = new InetSocketAddress(host, port);

			ChannelFuture futureConnect = null;

			/* Netty attempt to connect to server */
			futureConnect = boot.connect(address);

			channel = futureConnect.getChannel();

			futureConnect.awaitUninterruptibly(TIMEOUT, TIME_UNIT);

			/* Handle connection attempt errors */
			if (!futureConnect.isDone()) {
				log.warn("channel connect timeout; {}:{} ", host, port);
				return DDF_FeedEvent.CHANNEL_CONNECT_TIMEOUT;
			}

			if (!futureConnect.isSuccess()) {
				log.warn("channel connect unsuccessful; {}:{} ", host, port);
				return DDF_FeedEvent.CHANNEL_CONNECT_FAILURE;
			}

			/* Send login command to JERQ */
			DDF_FeedEvent writeEvent = blockingWrite(FeedDDF.tcpLogin(username,
					password));

			if (writeEvent == DDF_FeedEvent.COMMAND_WRITE_FAILURE) {
				return DDF_FeedEvent.COMMAND_WRITE_FAILURE;
			}

			/* Send VERSION 3 command to JERQ */
			writeEvent = blockingWrite(FeedDDF.tcpVersion(FeedDDF.VERSION_3));

			if (writeEvent == DDF_FeedEvent.COMMAND_WRITE_FAILURE) {
				return DDF_FeedEvent.COMMAND_WRITE_FAILURE;
			}

			/* Send timestamp command to JERQ */
			writeEvent = blockingWrite(FeedDDF.tcpGo(FeedDDF.SYMBOL_TIMESTAMP));

			if (writeEvent == DDF_FeedEvent.COMMAND_WRITE_FAILURE) {
				return DDF_FeedEvent.COMMAND_WRITE_FAILURE;
			}

			return DDF_FeedEvent.LOGIN_SENT;
		}

	}

	private class HeartbeatListener implements Runnable {

		private long delta;

		@Override
		public void run() {

			Thread.currentThread().setName("# ddf-heartbeat listener");

			try {
				while (!Thread.interrupted()) {
					checkTime();
					Thread.sleep(2000); // This must be less than
										// HEARTBEAT_TIMEOUT
				}

			} catch (final Exception e) {
				log.error(e.getMessage());
				Thread.currentThread().interrupt();
			}

		}

		private void checkTime() {

			/*
			 * If not currently logged in, keep the last heart beat updated so
			 * when we do query it, it will be fresh.
			 */
			if (loginHandler.isLoginActive() || !isConnected()) {
				lastHeartbeat.set(System.currentTimeMillis());
			} else {
				delta = System.currentTimeMillis() - lastHeartbeat.get();

				/*
				 * Close channel if time delta is greater than threshold and
				 * reset last heart beat.
				 */
				if (delta > HEARTBEAT_TIMEOUT) {
					log.debug("Heartbeat check failed - posting LINK_DISCONNECT event");
					log.debug("Heartbeat delta: " + delta);
					disconnect();
					lastHeartbeat.set(System.currentTimeMillis());
				}

			}
		}

	}

	// change how this is done

	public void setProxiedChannel(ChannelHandlerContext ctx, MessageEvent e,
			boolean success) {

		if (success) {
			this.channel = e.getChannel();

			// post ddf link connect
			postEvent(DDF_FeedEvent.LINK_CONNECT);

			final SimpleChannelHandler ddfHandler = new ChannelHandlerDDF(
					eventQueue, messageQueue);

			channel.getPipeline().addLast("ddf frame decoder",
					new MsgDeframerDDF());

			channel.getPipeline().addLast("ddf message decoder",
					new MsgDecoderDDF());

			// ### Encoders ###

			channel.getPipeline().addLast("ddf command encoder",
					new MsgEncoderDDF());

			channel.getPipeline().addLast("ddf data feed client", ddfHandler);

			socksConnectResult.add(true);
		} else {
			socksConnectResult.add(false);
		}
	}

	boolean connecting = false;

	@Override
	public void startUpProxy() {

		if (connecting == true) {
			log.error("Still connecting");
			return;
		}

		connecting = true;

		log.warn("startUpProxy() - connecting...");

		if (proxySettings == null) {
			log.error("Poxysettings are null, starting direct connect");
			startup();
			return;
		}

		/* Attempt to get current data server settings */
		DDF_Settings ddf_settings = null;
		try {
			ddf_settings = DDF_SettingsService.newSettings(username, password);
			if (!ddf_settings.isValidLogin()) {
				log.error("Posting SETTINGS_RETRIEVAL_FAILURE");
				postEvent(DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE);

				connecting = false;
				return;
			}
		} catch (final Exception e) {
			log.error("Posting SETTINGS_RETRIEVAL_FAILURE");
			postEvent(DDF_FeedEvent.SETTINGS_RETRIEVAL_FAILURE);

			connecting = false;
			return;
		}

		final DDF_Server server = ddf_settings.getServer(serverType);

		loginProxy(username, password, server);

		log.warn("startUpProxy() done connecting...");

		connecting = false;

	}

}
