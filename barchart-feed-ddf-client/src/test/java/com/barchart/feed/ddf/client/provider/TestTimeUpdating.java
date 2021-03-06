package com.barchart.feed.ddf.client.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.feed.api.MarketObserver;
import com.barchart.feed.api.Marketplace;
import com.barchart.feed.api.consumer.ConsumerAgent;
import com.barchart.feed.api.model.data.Market;
import com.barchart.feed.api.model.data.Market.Component;
import com.barchart.feed.api.model.meta.id.InstrumentID;
import com.barchart.feed.client.provider.BarchartMarketplace;
import com.barchart.feed.inst.Exchanges;

public class TestTimeUpdating {

	private static final Logger log = LoggerFactory.getLogger(
			TestTimeUpdating.class);
	
	private static final String[] insts = {
		"GOOG"//"ESU15"
	};
	
	public static void main(final String[] args) throws Exception {
		
		final String username = System.getProperty("barchart.username");
		final String password = System.getProperty("barchart.password");
		
		final Marketplace market = BarchartMarketplace.builder()
				.username(username)
				.password(password)
				.build();
		
		market.startup();
		
		Thread.sleep(500);
		
		final ConsumerAgent agent = market.register(marketObs(), Market.class);
		agent.include("GOOG").subscribe();
		agent.include("ESU15").subscribe();
		agent.activate();
		
		Thread.sleep(1000 * 60 * 1000);
		market.shutdown();
		
	}
	
	private static MarketObserver<Market> marketObs() {
		
		return new MarketObserver<Market>() {
			
			private boolean settled;
			
			@Override
			public void onNext(final Market m) {
				
				if(m.session().isSettled().value() != settled) {
					log.debug("SETTLED CHANGED");
					log.debug("{}", m);
					settled = m.session().isSettled().value();
				}
				
//				String updated;
//				if(m.updated().isNull()) {
//					updated = "NULL";
//				} else {
//					updated = m.updated().asDate().toString();
//				}
//				
//				String tradeTime;
//				if(m.trade().time().isNull()) {
//					tradeTime = "NULL";
//				} else {
//					tradeTime = m.trade().time().asDate().toString();
//				}
//				
//				if(m.change().contains(Component.TRADE)) {
//					log.debug("{} *TRADE UPDATED = {} TRADE = {}", 
//							m.instrument().symbol(), 
//							updated, 
//							tradeTime);
//				} else {
//					log.debug("{} MARKET UPDATED = {} TRADE = {}", 
//							m.instrument().symbol(), 
//							updated, 
//							tradeTime);
//				}
				
			}
			
		};
		
	}
	
}
