/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.historical.provider;

import static com.barchart.feed.ddf.historical.provider.CodecHelper.*;

import com.barchart.feed.ddf.historical.api.DDF_EntryTrend;
import com.barchart.feed.ddf.instrument.api.DDF_Instrument;
import com.barchart.feed.ddf.message.enums.DDF_TradeDay;
import com.barchart.util.ascii.ASCII;

class EntryMinsTrend extends Entry implements DDF_EntryTrend {

	public EntryMinsTrend(final DDF_Instrument instrument) {
		super(instrument);
	}

	// ///////////////////////////

	protected long priceResistance;
	protected long priceSupport;

	// ///////////////////////////

	@Override
	public long priceResistance() {
		return priceResistance;
	}

	@Override
	public long priceSupport() {
		return priceSupport;
	}

	//

	/**
	 * YYYY­MM­DD HH:MM,TRADING_DAY,
	 * 
	 * PRICE_SUPPORT,PRICE_RESISTANCE
	 */
	@Override
	public void decodeHead(final String[] inputArray) {

		millisUTC = decodeMinsTime(inputArray[0], instrument);

		ordTradeDay = DDF_TradeDay.fromMillisUTC(millisUTC).ord;

	}

	@Override
	public void decodeTail(final String[] inputArray) {

		priceSupport = decodeMantissa(inputArray[1], priceExponent());
		priceResistance = decodeMantissa(inputArray[2], priceExponent());

	}

	@Override
	public String encode() {

		final StringBuilder text = new StringBuilder(128);

		text.append(encodeMinsTime(millisUTC, instrument));
		text.append(ASCII.STRING_COMMA);

		text.append(encodeMantissa(priceSupport(), priceExponent()));
		text.append(ASCII.STRING_COMMA);

		text.append(encodeMantissa(priceResistance(), priceExponent()));
		// text.append(ASCII.STRING_COMMA);

		return text.toString();

	}

}
