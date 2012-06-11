/**
 * 
 */
package com.barchart.feed.ddf.datalink.api;

import java.util.Collection;
import java.util.Set;

import com.barchart.feed.base.instrument.enums.InstrumentField;
import com.barchart.feed.base.instrument.values.MarketInstrument;
import com.barchart.feed.base.market.enums.MarketEvent;
import com.barchart.feed.ddf.datalink.enums.DDF_FeedInterest;

/**
 * Represents a subscription to a single instrument for JERQ.
 * 
 */
public class Subscription {

	private final String instrument;
	private final Collection<DDF_FeedInterest> interests;

	/**
	 * @param instrument
	 *            The instrument to subscribe or unsubscribe to
	 * @param interests
	 *            A set of DDF_FeedInterests specifying what data for JERQ to
	 *            send a client. An empty set is interpreted as a request to
	 *            unsubscribe.
	 */
	public Subscription(final String instrument,
			final Collection<DDF_FeedInterest> interests) {
		this.instrument = instrument;
		this.interests = interests;
	}

	/**
	 * A feed-base friendly constructor for convenience.
	 * 
	 * @param instrument
	 * @param events
	 */
	public Subscription(final MarketInstrument instrument,
			final Set<MarketEvent> events) {
		this.instrument = instrument.get(InstrumentField.ID).toString();
		this.interests = DDF_FeedInterest.fromEvents(events);
	}

	/**
	 * Helper method returning the JERQ command to unsubscribe this
	 * subscription. Note: the "STOP " header is omitted for chaining requests.
	 * 
	 * @return The JERQ command to unsubscribe this subscription.
	 */
	public String unsubscribe() {
		return instrument + "=" + DDF_FeedInterest.from(interests);
	}

	/**
	 * Helper method returning the JERQ command to subscribe this subscription.
	 * Note: the "GO " header is omitted for chaining requests.
	 * 
	 * @return The JERQ command to subscribe this subscription.
	 */
	public String subscribe() {
		return instrument + "=" + DDF_FeedInterest.from(interests);
	}

	/**
	 * Returns the JERQ command to request this subscription.
	 */
	@Override
	public String toString() {
		return instrument + " " + DDF_FeedInterest.from(interests);
	}

	/**
	 * Returns the hash code of the instrument.
	 */
	@Override
	public int hashCode() {
		return instrument.hashCode();
	}

	/**
	 * Two subscriptions are equal if their instruments are the same, regardless
	 * of their interests.
	 */
	@Override
	public boolean equals(final Object o) {
		if (o == null) {
			return false;
		}

		if (!(o instanceof Subscription)) {
			return false;
		}

		final Subscription s = (Subscription) o;

		return (this.hashCode() == s.hashCode());
	}
}