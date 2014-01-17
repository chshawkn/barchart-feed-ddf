package com.barchart.feed.ddf.client.provider;

import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observer;

import com.barchart.feed.api.MarketObserver;
import com.barchart.feed.api.connection.Connection;
import com.barchart.feed.api.connection.Connection.Monitor;
import com.barchart.feed.api.connection.Connection.State;
import com.barchart.feed.api.consumer.ConsumerAgent;
import com.barchart.feed.api.consumer.MarketService;
import com.barchart.feed.api.consumer.MetadataService.Result;
import com.barchart.feed.api.model.data.Book;
import com.barchart.feed.api.model.data.Market;
import com.barchart.feed.api.model.data.Market.LastPrice;
import com.barchart.feed.api.model.meta.Instrument;
import com.barchart.feed.client.provider.BarchartMarketProvider;

public class TestBarchartMarketProvider {

	private static final Logger log = LoggerFactory.getLogger(
			TestBarchartMarketProvider.class);
	
	private static final String[] insts = {"_S_SP_KCH14_KCK14"};
	
	public static void main(final String[] args) throws Exception {
		
		final String username = System.getProperty("barchart.username");
		final String password = System.getProperty("barchart.password");
		
		final MarketService market = new BarchartMarketProvider(username, password);
		
		final CountDownLatch lock = new CountDownLatch(1);
		
		market.bindConnectionStateListener(listener(lock));
		market.startup();
		
		lock.await();
		
		final ConsumerAgent agent1 = market.register(marketObs(), Book.class);
		
		agent1.include(insts).subscribe(instObs());
		Thread.sleep(2 * 60 * 60 * 1000);
		
		agent1.exclude(insts).subscribe(instObs());
		Thread.sleep(5 * 1000);
		
		log.debug("Shutting down");
		market.shutdown();
		
		Thread.sleep(5 * 1000);
		
	}
	
	private static Monitor listener(final CountDownLatch lock) { 
		return new Monitor() {

			@Override
			public void handle(State state, Connection connection) {
				
				log.debug("New State = {}", state);
				
				if(state == State.CONNECTED) {
					lock.countDown();
				}
			}
		};
	}
	
	private static MarketObserver<Book> marketObs() {
		
		return new MarketObserver<Book>() {

			@Override
			public void onNext(final Book b) {
				
				//final Book b = v.book();
				final Book.Top t = b.top();
				
				System.out.println(t.ask().price() + " " + t.bid().price());
				
			}
		};
	}
	
	private static Observer<Result<Instrument>> instObs() {
		
		return new Observer<Result<Instrument>>() {

			@Override
			public void onCompleted() {
				log.debug("Lookup and registration complete");
			}

			@Override
			public void onError(Throwable e) {
				log.error("Exception in lookup and registration \n{}", e);
			}

			@Override
			public void onNext(Result<Instrument> args) {
				log.debug("New Instrument Lookup and Registration");
				//
			}
			
		};
	}
	
	
}
