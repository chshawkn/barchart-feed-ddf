package com.barchart.feed.test.replay;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.feed.api.Agent;
import com.barchart.feed.api.MarketObserver;
import com.barchart.feed.api.model.data.Market;
import com.barchart.feed.api.model.data.Session;
import com.barchart.feed.inst.provider.Exchanges;
import com.barchart.util.value.api.Price;

public class TestXFSettle {

	protected static final Logger log = LoggerFactory
			.getLogger(TestXFSettle.class);

	@Test
	public void testXFSettle() throws Exception {

		final BarchartMarketplaceReplay market =
				new BarchartMarketplaceReplay();

		final Agent agent = market.newAgent(Market.class, obs);

		agent.include(Exchanges.fromName("BMF"));

		FeedReplay.builder()
				.source(FeedReplay.class.getResource("/XF_20140113.txt"))
				.symbols("XFK4")
				.build(market.maker())
				.run();

		final Market v = market.snapshot("XFK14");

		log.debug("Previous = " + v.sessionSet().session(Session.Type.DEFAULT_PREVIOUS).settle().toString() +
				" Current = " + v.session().settle());

	}

	@Test
	public void testXFSettle_JJ() throws Exception {

		final BarchartMarketplaceReplay marketplace =
				new BarchartMarketplaceReplay();

		final SettleObserver so = new SettleObserver();
		final Agent agent = marketplace.newAgent(Market.class, so);

		agent.include(Exchanges.fromName("BMF"));

		FeedReplay.builder()
				.source(FeedReplay.class.getResource("/XF_20140113.txt"))
				.build(marketplace.maker())
				.run();

		System.out.println("Final settlement states:");
		for (final String sym : new String[] {
				"XFH14", "XFK14", "XFN14", "XFU14"
		}) {
			final Market snapshot = marketplace.snapshot(sym);
			System.out.println(sym
					+ ": settle="
					+ snapshot.sessionSet()
							.session(Session.Type.DEFAULT_CURRENT).settle()
							.asDouble()
					+ ", prevSettle="
					+ snapshot.sessionSet()
							.session(Session.Type.DEFAULT_PREVIOUS).settle()
							.asDouble());
		}

	}

	private static class SettleObserver implements MarketObserver<Market> {

		@Override
		public void onNext(final Market m) {
		}

	}

	static MarketObserver<Market> obs = new MarketObserver<Market>() {

		private final Price prev = Price.NULL;
		private final Price cur = Price.NULL;

		@Override
		public void onNext(final Market v) {

//			if(!prev.equals(v.sessionSet().session(Type.DEFAULT_PREVIOUS).settle()) ||
//					!cur.equals(v.session().settle())) {
//
//				log.debug("Previous = " + v.sessionSet().session(Type.DEFAULT_PREVIOUS).settle().toString() +
//					" Current = " + v.session().settle() + " ");
//
//				prev = v.sessionSet().session(Type.DEFAULT_PREVIOUS).settle();
//				cur = v.session().settle();
//
//			}

//			if(cur == Price.NULL) {
//				System.out.println();
//			} else {
//				System.out.println();
//			}
//
//			log.debug("Previous = " + v.sessionSet().session(Type.DEFAULT_PREVIOUS).settle().toString() +
//					" Current = " + v.session().settle());
//			prev = v.sessionSet().session(Type.DEFAULT_PREVIOUS).settle();
//			cur = v.session().settle();

		}

	};

}