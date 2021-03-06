/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.historical.example;

import com.barchart.feed.api.model.meta.Instrument;
import com.barchart.feed.base.market.api.MarketDisplay;
import com.barchart.feed.base.provider.MarketDisplayBaseImpl;
import com.barchart.feed.base.values.api.PriceValue;
import com.barchart.feed.base.values.provider.ValueBuilder;

/**
 * helper class to demonstrate batch processing of historical data
 */
class PriceExtreme {

	Instrument inst;
	
	static final MarketDisplay display = new MarketDisplayBaseImpl();

	PriceExtreme(final Instrument instrument) {
		this.inst = instrument;
	}

	long mantissaMin = Long.MAX_VALUE;

	long mantissaMax = Long.MIN_VALUE;

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {

		final int exponent = (int)inst.displayFraction().decimalExponent();

		final PriceValue priceMin = ValueBuilder
				.newPrice(mantissaMin, exponent);
		final PriceValue priceMax = ValueBuilder
				.newPrice(mantissaMax, exponent);

		final String stringMin = display.priceText(priceMin, 
				inst.displayFraction());
		final String stringMax = display.priceText(priceMax, 
				inst.displayFraction());

		return String
				.format("minimum : %s  maximum : %s", stringMin, stringMax);

	}

}