package com.barchart.feed.client.provider;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.feed.api.Agent;
import com.barchart.feed.api.Feed;
import com.barchart.feed.api.MarketCallback;
import com.barchart.feed.api.connection.ConnectionFuture;
import com.barchart.feed.api.connection.ConnectionStateListener;
import com.barchart.feed.api.connection.TimestampListener;
import com.barchart.feed.api.data.Cuvol;
import com.barchart.feed.api.data.Exchange;
import com.barchart.feed.api.data.Instrument;
import com.barchart.feed.api.data.Market;
import com.barchart.feed.api.data.MarketData;
import com.barchart.feed.api.data.OrderBook;
import com.barchart.feed.api.data.TopOfBook;
import com.barchart.feed.api.data.Trade;
import com.barchart.feed.api.enums.MarketEventType;
import com.barchart.feed.api.inst.InstrumentFuture;
import com.barchart.feed.api.inst.InstrumentFutureMap;
import com.barchart.feed.ddf.datalink.api.DDF_FeedClientBase;
import com.barchart.feed.ddf.datalink.api.DDF_MessageListener;
import com.barchart.feed.ddf.datalink.enums.DDF_Transport;
import com.barchart.feed.ddf.datalink.provider.DDF_FeedClientFactory;
import com.barchart.feed.ddf.instrument.provider.DDF_InstrumentProvider;
import com.barchart.feed.ddf.instrument.provider.InstrumentDBProvider;
import com.barchart.feed.ddf.instrument.provider.LocalInstrumentDBMap;
import com.barchart.feed.ddf.instrument.provider.ServiceDatabaseDDF;
import com.barchart.feed.ddf.market.provider.DDF_Marketplace;
import com.barchart.feed.ddf.message.api.DDF_BaseMessage;
import com.barchart.feed.ddf.message.api.DDF_ControlTimestamp;
import com.barchart.feed.ddf.message.api.DDF_MarketBase;
import com.barchart.util.value.api.Factory;
import com.barchart.util.value.api.FactoryLoader;

public class BarchartFeed implements Feed {
	
	private static final Logger log = LoggerFactory
			.getLogger(BarchartFeed.class);
	
	/* Value api factory */
	private static final Factory factory = FactoryLoader.load();
	
	/* Used if unable to retrieve system default temp directory */
	private static final String TEMP_DIR = "C:\\windows\\temp\\";
	private final File dbFolder;
	private static final long DB_UPDATE_TIMEOUT = 60; // seconds
	
	private final DDF_FeedClientBase connection;
	private final DDF_Marketplace maker;
	private final ExecutorService executor;
	
	@SuppressWarnings("unused")
	private volatile ConnectionStateListener stateListener;
	
	private final CopyOnWriteArrayList<TimestampListener> timeStampListeners =
			new CopyOnWriteArrayList<TimestampListener>();
	
	
	public BarchartFeed(final String username, final String password) {
		this(username, password, Executors.newCachedThreadPool());
	}
	
	public BarchartFeed(final String username, final String password, 
			final ExecutorService ex) {
		this(username, password, ex, getTempFolder());
	}
	
	public BarchartFeed(final String username, final String password, 
			final ExecutorService ex, final File dbFolder) {
		
		executor  = ex;
		this.dbFolder = dbFolder;
		
		connection = DDF_FeedClientFactory.newConnectionClient(
				DDF_Transport.TCP, username, password, executor);
		
		connection.bindMessageListener(msgListener);
		
		maker = DDF_Marketplace.newInstance(connection);
		
	}
	
	/*
	 * Returns the default temp folder
	 */
	private static File getTempFolder() {
		
		try {
			
			return File.createTempFile("temp", null).getParentFile();
			
		} catch (IOException e) {
			log.warn("Unable to retrieve system temp folder, using default {}", 
					TEMP_DIR);
			return new File(TEMP_DIR);
		}
		
	}
	
	/* ***** ***** ***** ConnectionLifecycle ***** ***** ***** */
	
	// FIXME
	/**
	 * Starts the data feed asynchronously. Notification of login success is
	 * reported by FeedStateListeners which are bound to this object.
	 * <p>
	 * Constructs a new feed client with the user's user name and password. The
	 * transport protocol defaults to TCP and a default executor are used.
	 * 
	 * @param username
	 * @param password
	 */
	
	private final AtomicBoolean isStartingup = new AtomicBoolean(false);
	private final AtomicBoolean isShuttingdown = new AtomicBoolean(false);
	
	@Override
	public synchronized ConnectionFuture<Feed> startup() {
		
		// Consider dummy future?
		if(isStartingup.get()) {
			throw new IllegalStateException("Startup called while already starting up");
		}
		
		if(isShuttingdown.get()) {
			throw new IllegalStateException("Startup called while shutting down");
		}
		
		isStartingup.set(true);
		
		final ConnectionFuture<Feed> future = new ConnectionFuture<Feed>();
		
		executor.execute(new StartupRunnable(future));
		
		return future;
		
	}
	
	private final class StartupRunnable implements Runnable {

		private final ConnectionFuture<Feed> future;
		
		StartupRunnable(final ConnectionFuture<Feed> future) {
			this.future = future;
		}

		/*
		 * Ensures instrument database is installed/updated before
		 * user is able to send subscriptions to JERQ
		 */
		@Override
		public void run() {
			
			try {
			
				final LocalInstrumentDBMap dbMap = InstrumentDBProvider.getMap(dbFolder);
				
				final ServiceDatabaseDDF dbService = new ServiceDatabaseDDF(dbMap, executor);
				
				DDF_InstrumentProvider.bind(dbService);
				
				final Future<Boolean> dbUpdate = executor.submit(
						InstrumentDBProvider.updateDBMap(dbFolder, dbMap));
				
				boolean dbok = dbUpdate.get(DB_UPDATE_TIMEOUT, TimeUnit.SECONDS);
				
				if(!dbok) {
					throw new Exception("Failed to update DB");
				}
				
				connection.startup();
			
			} catch (final Throwable t) {
				
				isStartingup.set(false);
				
				future.fail(t);
				
				return;
			}
			
			isStartingup.set(false);
			
			future.succeed(BarchartFeed.this);
			
		}
		
	}
	
	@Override
	public synchronized ConnectionFuture<Feed> shutdown() {

		// Consider dummy future?
		if(isStartingup.get()) {
			throw new IllegalStateException("Shutdown called while shutting down");
		}
		
		if(isShuttingdown.get()) {
			throw new IllegalStateException("Shutdown called while already shutting down");
		}
		
		isShuttingdown.set(true);
		
		final ConnectionFuture<Feed> future = new ConnectionFuture<Feed>();
		
		executor.execute(new ShutdownRunnable(future));
		
		return future;

	}
	
	private final class ShutdownRunnable implements Runnable {

		private final ConnectionFuture<Feed> future;
		
		ShutdownRunnable(final ConnectionFuture<Feed> future) {
			this.future = future;
		}
		
		@Override
		public void run() {
			
			try {
				
				if(maker != null) {
					maker.clearAll();
				}
	
				connection.shutdown();
				
			} catch (final Throwable t) {
				
				isShuttingdown.set(false);
				
				future.fail(t);
				
				return;
			}
			
			
			isShuttingdown.set(false);
			
			future.succeed(BarchartFeed.this);
			
		}
		
	}
	
	/*
	 * This is the default message listener. Users wishing to handle raw
	 * messages will need to implement their own feed client.
	 */
	private final DDF_MessageListener msgListener = new DDF_MessageListener() {

		@Override
		public void handleMessage(final DDF_BaseMessage message) {

			if (message instanceof DDF_ControlTimestamp) {
				for (final TimestampListener listener : timeStampListeners) {
					listener.listen(factory.newTime(((DDF_ControlTimestamp) message)
							.getStampUTC().asMillisUTC(), ""));
				}
			}

			if (message instanceof DDF_MarketBase) {
				final DDF_MarketBase marketMessage = (DDF_MarketBase) message;
				maker.make(marketMessage);
			}

		}

	};
	
	@Override
	public void bindConnectionStateListener(final ConnectionStateListener listener) {

		stateListener = listener;

		if (connection != null) {
			connection.bindStateListener(listener);
		} else {
			throw new RuntimeException("Connection state listener already bound");
		}

	}
	
	@Override
	public void bindTimestampListener(final TimestampListener listener) {
		
		if(listener != null) {
			timeStampListeners.add(listener);
		}
		
	}
	
	/* ***** ***** ***** InstrumentService ***** ***** ***** */
	
	@Override
	public Instrument lookup(final CharSequence symbol) {
		return DDF_InstrumentProvider.find(symbol);
	}
	
	@Override
	public InstrumentFuture lookupAsync(final CharSequence symbol) {
		// TODO
		throw new UnsupportedOperationException();
	}
	
	@Override
	public Map<CharSequence, Instrument> lookup(
			final Collection<? extends CharSequence> symbolList) {
		return DDF_InstrumentProvider.find(symbolList);
	}

	@Override
	public InstrumentFutureMap<CharSequence> lookupAsync(
			final Collection<? extends CharSequence> symbols) {
		// TODO
		throw new UnsupportedOperationException();
	}

	/* ***** ***** ***** AgentBuilder ***** ***** ***** */
	
	@Override
	public <V extends MarketData<V>> Agent newAgent(final Class<V> dataType, 
			final MarketCallback<V> callback,	final MarketEventType... types) {
		
		return maker.newAgent(dataType, callback, types);
		
	}
	
	/* ***** ***** ***** Helper subscribe methods ***** ***** ***** */
	
	@Override
	public <V extends MarketData<V>> Agent subscribe(final Class<V> clazz,
			final MarketCallback<V> callback, final MarketEventType[] types,
			final String... symbols) {
		
		final Agent agent = newAgent(clazz, callback, types);
		
		agent.include(symbols);
		
		return agent;
	}

	@Override
	public <V extends MarketData<V>> Agent subscribe(final Class<V> clazz,
			final MarketCallback<V> callback, final MarketEventType[] types,
			final Instrument... instruments) {
		
		final Agent agent = newAgent(clazz, callback, types);
		
		agent.include(instruments);
		
		return agent;
	}

	@Override
	public <V extends MarketData<V>> Agent subscribe(final Class<V> clazz,
			final MarketCallback<V> callback, final MarketEventType[] types,
			final Exchange... exchanges) {

		final Agent agent = newAgent(clazz, callback, types);
		
		agent.include(exchanges);
		
		return agent;
	}

	@Override
	public Agent subscribeMarket(final MarketCallback<Market> callback,
			final String... symbols) {
		
		final Agent agent = newAgent(Market.class, callback, MarketEventType.ALL);
		
		agent.include(symbols);
		
		return agent;
	}

	@Override
	public Agent subscribeTrade(final MarketCallback<Trade> lastTrade,
			final String... symbols) {
		
		final Agent agent = newAgent(Trade.class, lastTrade, MarketEventType.TRADE);
		
		agent.include(symbols);
		
		return agent;
	}

	@Override
	public Agent subscribeBook(final MarketCallback<OrderBook> book,
			final String... symbols) {
		
		final Agent agent = newAgent(OrderBook.class, book, 
				MarketEventType.BOOK_SNAPSHOT, MarketEventType.BOOK_UPDATE);
		
		agent.include(symbols);
		
		return agent;
	}

	@Override
	public Agent subscribeTopOfBook(final MarketCallback<TopOfBook> top,
			final String... symbols) {
		
		final Agent agent = newAgent(TopOfBook.class, top, 
				MarketEventType.BOOK_SNAPSHOT, MarketEventType.BOOK_UPDATE);
		
		return agent;
	}

	@Override
	public Agent subscribeCuvol(final MarketCallback<Cuvol> cuvol,
			final String... symbols) {
		
		final Agent agent = newAgent(Cuvol.class, cuvol, 
				MarketEventType.CUVOL_SNAPSHOT, MarketEventType.CUVOL_UPDATE);
		
		return agent;
	}

}