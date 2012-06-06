/**
 * 
 */
package com.barchart.feed.ddf.datalink.provider;

import static com.barchart.feed.ddf.datalink.provider.DDF_FeedClientFactory.TransportProtocol.TCP;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.feed.ddf.datalink.api.DDF_FeedClient;
import com.barchart.feed.ddf.datalink.api.DDF_MessageListener;
import com.barchart.feed.ddf.datalink.api.Subscription;
import com.barchart.feed.ddf.datalink.enums.DDF_FeedInterest;
import com.barchart.feed.ddf.message.api.DDF_BaseMessage;

/**
 * @author g-litchfield
 * 
 */
public class TestLogins {

	private static final Logger log = LoggerFactory.getLogger(TestLogins.class);

	public static void main(final String[] args) throws Exception {

		final String username = System.getProperty("barchart.username");
		final String password = System.getProperty("barchart.password");

		final Executor runner = new Executor() {

			private final AtomicLong counter = new AtomicLong(0);

			final String name = "# DDF Feed Client - "
					+ counter.getAndIncrement();

			@Override
			public void execute(final Runnable task) {
				new Thread(task, name).start();
			}

		};

		final DDF_FeedClient client =
				DDF_FeedClientFactory.newInstance(TCP, username, password,
						runner);

		final DDF_MessageListener handler = new DDF_MessageListener() {

			@Override
			public void handleMessage(final DDF_BaseMessage message) {
				log.debug(message.toString());
			}
		};

		client.bindMessageListener(handler);

		// Initial login
		client.startup();

		final Set<DDF_FeedInterest> interests = new HashSet<DDF_FeedInterest>();
		interests.addAll(DDF_FeedInterest.setValues());
		final Subscription sub = new Subscription("_S_SP_CLM2_CLN2", interests);

		client.subscribe(sub);

		sleep(100000);

		// log.debug("*****************************************  Unsubscribing");
		//
		// client.unsubscribe(sub);
		//
		// sleep(10000);
		//
		// log.debug("*****************************************  Resubscribing");
		//
		// client.subscribe(sub);
		//
		// sleep(10000);

		client.shutdown();
	}

	private static void sleep(final int mills) {
		try {
			Thread.sleep(mills);
		} catch (final InterruptedException e) {
			e.printStackTrace();
		}
	}

}
