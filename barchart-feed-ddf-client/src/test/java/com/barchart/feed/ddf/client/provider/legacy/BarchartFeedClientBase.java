/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
/**
 * 
 * The core DDF class which encapsulates all the core functionality a new user
 * will need to get started.
 * <p>
 * Instances are created using the public constructor and require a
 * valid user name and password. Optional parameters include specifying the
 * transport protocol and providing an executor.
 * <p>
 * The data feed is started and stopped using the startup() and shutdown()
 * methods. Note that these are non-blocking calls, therefore applications 
 * requiring actions upon successful login should instantiate and bind a
 * FeedStatusListener to the client.
 * <p>
 * 
 * 
 */
package com.barchart.feed.ddf.client.provider.legacy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.feed.api.connection.ConnectionStateListener;
import com.barchart.feed.api.connection.TimestampListener;
import com.barchart.feed.api.model.meta.Instrument;
import com.barchart.feed.base.market.api.MarketTaker;
import com.barchart.feed.base.market.enums.MarketField;
import com.barchart.feed.ddf.datalink.api.DDF_FeedClientBase;
import com.barchart.feed.ddf.datalink.api.DDF_MessageListener;
import com.barchart.feed.ddf.instrument.provider.DDF_InstrumentProvider;
import com.barchart.feed.ddf.market.api.DDF_MarketProvider;
import com.barchart.feed.ddf.market.provider.DDF_MarketService;
import com.barchart.feed.ddf.message.api.DDF_BaseMessage;
import com.barchart.feed.ddf.message.api.DDF_ControlTimestamp;
import com.barchart.feed.ddf.message.api.DDF_MarketBase;
import com.barchart.util.value.api.Factory;
import com.barchart.util.value.api.FactoryLoader;
import com.barchart.util.values.api.Value;

/**
 * The entry point for Barchart data feed services.
 */
abstract class BarchartFeedClientBase {

	@SuppressWarnings("unused")
	private static final Logger log = LoggerFactory
			.getLogger(BarchartFeedClientBase.class);

	private static final Factory factory = FactoryLoader.load();
	
	protected volatile DDF_FeedClientBase feed = null;

	protected DDF_MarketProvider maker;

	private final CopyOnWriteArrayList<TimestampListener> timeStampListeners =
			new CopyOnWriteArrayList<TimestampListener>();

	private ConnectionStateListener stateListener;

	public BarchartFeedClientBase() {
		maker = DDF_MarketService.newInstance();
	}
	
	/*
	 * Handles login. Non-blocking.
	 */
	protected void setClient(final DDF_FeedClientBase client, final boolean proxy) {

		maker.clearAll();

		if (feed != null) {
			feed.shutdown();
		}

		feed = client;

		feed.bindMessageListener(msgListener);

		if (stateListener != null) {
			feed.bindStateListener(stateListener);
		}

		if(proxy){
			feed.startUpProxy();
		} else {
			feed.startup();
		}

	}
	
	/**
	 * Shuts down the data feed and clears all registered market takers.
	 */
	public void shutdown() {

		maker.clearAll();

		if (feed != null) {
			feed.shutdown();
			feed = null;
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

	/**
	 * Applications which need to react to the connectivity state of the feed
	 * instantiate a FeedStateListener and bind it to the client.
	 * 
	 * @param listener
	 *            The listener to be bound.
	 */
	public void bindFeedStateListener(final ConnectionStateListener listener) {

		stateListener = listener;

		if (feed != null) {
			feed.bindStateListener(listener);
		}

	}

	/**
	 * Applications which require time-stamp or heart-beat messages from the
	 * data server instantiate a DDF_TimestampListener and bind it to the
	 * client.
	 * 
	 * @param listener
	 */
	public void bindTimestampListener(final TimestampListener listener) {
		timeStampListeners.add(listener);
	}

	/**
	 * Adds a market taker to the client. This performs instrument registration
	 * with the market maker as well as subscribing to the required data from
	 * the feed.
	 * 
	 * @param taker
	 *            The market taker to be added.
	 * @return True if the taker was successfully added.
	 */
	public boolean isTakerRegistered(final MarketTaker<?> taker) {
		return maker.isRegistered(taker);
	}

	/**
	 * Adds a market taker to the client. This performs instrument registration
	 * with the market maker as well as subscribing to the required data from
	 * the feed.
	 * 
	 * @param taker
	 *            The market taker to be added.
	 * @return True if the taker was successfully added.
	 */
	public <V extends Value<V>> boolean addTaker(final MarketTaker<V> taker) {
		return maker.register(taker);
	}

	/**
	 * Updates a taker. This handles any instrument registration/unregistration
	 * needed with the feed client.
	 * 
	 * @param taker
	 * @return
	 */
	public <V extends Value<V>> boolean updateTaker(final MarketTaker<V> taker) {
		return maker.update(taker);
	}

	/**
	 * Removes a market taker from the client. If no other takers require its
	 * instruments, they are unsubscribed from the feed.
	 * 
	 * @param taker
	 *            THe market taker to be removed.
	 * @return True if the taker was successfully removed.
	 */
	public <V extends Value<V>> boolean removeTaker(final MarketTaker<V> taker) {
		return maker.unregister(taker);
	}

	/**
	 * Retrieves the instrument object denoted by symbol. The local instrument
	 * cache will be checked first. If the instrument is not stored locally, a
	 * remote call to the instrument service is made.
	 * 
	 * @return NULL_INSTRUMENT if the symbol is not resolved.
	 */
	public List<Instrument> lookup(final String symbol) {
		return DDF_InstrumentProvider.find(symbol);
	}

	/**
	 * Retrieves a list of instrument objects denoted by symbols provided. The
	 * local instrument cache will be checked first. If any instruments are not
	 * stored locally, a remote call to the instrument service is made.
	 * 
	 * @return An empty list if no symbols can be resolved.
	 */
	public Map<CharSequence, List<Instrument>> lookup(final List<String> symbolList) {
		return DDF_InstrumentProvider.find(symbolList);
	}

	/**
	 * Makes a query to the market maker for a snapshot of a market field for a
	 * specific instrument. The returned values are frozen and disconnected from
	 * live market.
	 * 
	 * @return NULL_VALUE for all fields if market is not present.
	 */
	public <S extends Instrument, V extends Value<V>> V take(
			final S instrument, final MarketField<V> field) {
		return maker.take(instrument, field);
	}

}