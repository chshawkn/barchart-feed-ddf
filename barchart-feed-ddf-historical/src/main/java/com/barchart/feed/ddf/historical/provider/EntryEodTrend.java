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

// TODO: Auto-generated Javadoc
class EntryEodTrend extends Entry implements DDF_EntryTrend {

	/**
	 * Instantiates a new entry eod trend.
	 *
	 * @param instrument the instrument
	 */
	public EntryEodTrend(final DDF_Instrument instrument) {
		super(instrument);
	}

	// ///////////////////////////

	protected long priceResistance;
	protected long priceSupport;

	// ///////////////////////////

	/* (non-Javadoc)
	 * @see com.barchart.feed.ddf.historical.api.DDF_EntryTrend#priceResistance()
	 */
	@Override
	public long priceResistance() {
		return priceResistance;
	}

	/* (non-Javadoc)
	 * @see com.barchart.feed.ddf.historical.api.DDF_EntryTrend#priceSupport()
	 */
	@Override
	public long priceSupport() {
		return priceSupport;
	}

	//

	/**
	 * SYMBOL,YYYY­MM­DD,
	 * 
	 * PRICE_SUPPORT,PRICE_RESISTANCE.
	 *
	 * @param inputArray the input array
	 */
	@Override
	public void decodeHead(final String[] inputArray) {

		instrument = decodeInstrument(inputArray[0]);

		millisUTC = decodeEodTime(inputArray[1], instrument);

		ordTradeDay = DDF_TradeDay.fromMillisUTC(millisUTC).ord;

	}

	/* (non-Javadoc)
	 * @see com.barchart.feed.ddf.historical.provider.Entry#decodeTail(java.lang.String[])
	 */
	@Override
	public void decodeTail(final String[] inputArray) {

		priceSupport = decodeMantissa(inputArray[2], priceExponent());
		priceResistance = decodeMantissa(inputArray[3], priceExponent());

	}

	/* (non-Javadoc)
	 * @see com.barchart.feed.ddf.historical.provider.Entry#encode()
	 */
	@Override
	public String encode() {

		final StringBuilder text = new StringBuilder(128);

		text.append(encodeInstrument(instrument, millisUTC));
		text.append(ASCII.STRING_COMMA);

		text.append(encodeEodTime(millisUTC, instrument));
		text.append(ASCII.STRING_COMMA);

		text.append(encodeMantissa(priceSupport(), priceExponent()));
		text.append(ASCII.STRING_COMMA);

		text.append(encodeMantissa(priceResistance(), priceExponent()));
		// text.append(ASCII.STRING_COMMA);

		return text.toString();

	}

}