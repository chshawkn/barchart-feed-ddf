/**
 * 
 */
package com.barchart.feed.ddf.datalink.provider;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import com.barchart.feed.ddf.datalink.api.DDF_MessageListener;
import com.barchart.feed.ddf.message.api.DDF_BaseMessage;

/**
 * @author g-litchfield
 * 
 */
public class TestListenerClientDDF {

	static final String allLocal = "0.0.0.0";
	static final String localHost = "127.0.0.1";

	static final int portIn = 8000;

	/**
	 * @param args
	 */
	public static void main(final String[] args) throws Exception {

		final Executor runner = new Executor() {

			private final AtomicLong counter = new AtomicLong(0);

			final String name = "# DDF Feed Listener - "
					+ counter.getAndIncrement();

			@Override
			public void execute(final Runnable task) {
				new Thread(task, name).start();
			}

		};

		final DDF_MessageListener msgListener = new DDF_MessageListener() {

			@Override
			public void handleMessage(final DDF_BaseMessage message) {

				System.out.println(message.toString());

			}

		};

		final ListenerClientDDF client = new ListenerClientDDF(8000, runner);

		client.bindMessageListener(msgListener);

		client.startup();

		Thread.sleep(1000000);

		client.shutdown();

	}

}