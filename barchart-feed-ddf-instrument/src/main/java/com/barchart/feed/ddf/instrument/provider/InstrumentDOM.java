/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.instrument.provider;

import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.BASE_CODE_DDF;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.EXCHANGE_COMMENT;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.EXCHANGE_DDF;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.LOOKUP;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.PRICE_POINT_VALUE;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.PRICE_TICK_INCREMENT;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.STATUS;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.SYMBOL_CODE_CFI;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.SYMBOL_COMMENT;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.SYMBOL_EXPIRE;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.SYMBOL_HIST;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.SYMBOL_REALTIME;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.SYMBOL_DDF_REAL;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.GUID;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.TIME_ZONE_DDF;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.SYMBOL_DDF_EXPIRE_MONTH;
import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.SYMBOL_DDF_EXPIRE_YEAR;
import static com.barchart.feed.ddf.util.HelperXML.XML_PASS;
import static com.barchart.feed.ddf.util.HelperXML.XML_STOP;
import static com.barchart.feed.ddf.util.HelperXML.xmlByteDecode;
import static com.barchart.feed.ddf.util.HelperXML.xmlDecimalDecode;
import static com.barchart.feed.ddf.util.HelperXML.xmlStringDecode;
import static com.barchart.feed.ddf.util.HelperXML.xmlTimeDecode;
import static com.barchart.util.values.provider.ValueBuilder.newPrice;
import static com.barchart.util.values.provider.ValueBuilder.newText;

import org.w3c.dom.Element;

import com.barchart.feed.base.instrument.enums.CodeCFI;
import com.barchart.feed.base.instrument.enums.InstrumentField;
import com.barchart.feed.ddf.instrument.enums.DDF_InstrumentField;
import com.barchart.feed.ddf.symbol.enums.DDF_Exchange;
import com.barchart.feed.ddf.symbol.enums.DDF_TimeZone;
import com.barchart.feed.ddf.util.enums.DDF_Fraction;
import com.barchart.util.values.api.PriceValue;
import com.barchart.util.values.api.TimeValue;
import com.barchart.util.values.provider.ValueBuilder;

class InstrumentDOM extends InstrumentDDF implements CodecDOM {

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.barchart.feed.ddf.instrument.provider.CodecDOM#decodeXML(org.w3c.
	 * dom.Element)
	 */
	@Override
	public void decodeXML(final Element tag) throws Exception {

		// lookup status

		final String statusCode = xmlStringDecode(tag, STATUS, XML_STOP);

		final StatusXML status = StatusXML.fromCode(statusCode);

		if (!status.isFound()) {

			final String lookup = xmlStringDecode(tag, LOOKUP, XML_STOP);

			throw new SymbolNotFoundException(lookup);

		}

		// decode DOM

		final String guid = xmlStringDecode(tag, GUID, XML_STOP);

		final String symbolHist = xmlStringDecode(tag, SYMBOL_HIST, XML_STOP);

		final String symbolReal = xmlStringDecode(tag, SYMBOL_REALTIME, XML_STOP);
		
		final String symbolDDFReal = xmlStringDecode(tag, SYMBOL_DDF_REAL, XML_STOP);

		final byte exchCode = xmlByteDecode(tag, EXCHANGE_DDF, XML_PASS); // XXX

		final byte baseCode = xmlByteDecode(tag, BASE_CODE_DDF, XML_STOP);

		final String codeCFI = xmlStringDecode(tag, SYMBOL_CODE_CFI, XML_PASS);

		final String zoneCode = xmlStringDecode(tag, TIME_ZONE_DDF, XML_STOP);

		final String symolComment = xmlStringDecode(tag, SYMBOL_COMMENT,
				XML_PASS);

		final String exchangeComment = xmlStringDecode(tag, EXCHANGE_COMMENT,
				XML_PASS);

		final TimeValue expire = xmlTimeDecode(tag, SYMBOL_EXPIRE, XML_PASS);

		//

		// month code for exp of futures contract

		final String ddf_expire_month = xmlStringDecode(tag,
				SYMBOL_DDF_EXPIRE_MONTH, XML_PASS);
		final String ddf_expire_year = xmlStringDecode(tag,
				SYMBOL_DDF_EXPIRE_YEAR, XML_PASS);

		final DDF_TimeZone zone = DDF_TimeZone.fromCode(zoneCode);

		final DDF_Exchange exchange = DDF_Exchange.fromCode(exchCode);

		final DDF_Fraction frac = DDF_Fraction.fromBaseCode(baseCode);

		final long priceStepMantissa = xmlDecimalDecode(frac, tag,
				PRICE_TICK_INCREMENT, XML_STOP);

		final String pricePointString = xmlStringDecode(tag, PRICE_POINT_VALUE,
				XML_PASS);

		PriceValue pricePoint = ValueBuilder.newPrice(0);

		if (pricePointString != null) {

			try {
				pricePoint = ValueBuilder.newPrice(Double
						.valueOf(pricePointString));
			} catch (Exception e) {
			}

		}

		final PriceValue priceStep = newPrice(priceStepMantissa,
				frac.decimalExponent);

		/* GENERIC */

		set(InstrumentField.ID, newText(guid));
		set(InstrumentField.SYMBOL, newText(symbolReal));
		set(InstrumentField.DESCRIPTION, newText(symolComment));
		set(InstrumentField.TYPE, CodeCFI.fromCode(codeCFI));
		set(InstrumentField.EXCHANGE_ID, newText(exchange.name()));
		set(InstrumentField.FRACTION, frac.fraction);
		set(InstrumentField.PRICE_STEP, priceStep);
		set(InstrumentField.PRICE_POINT, pricePoint);

		// TODO
		// final VarCalendar calendar = new VarCalendar();
		// set(InstrumentField.CALENDAR, calendar);
		set(InstrumentField.TIME_ZONE, newText(zone.code));
		set(InstrumentField.DATE_FINISH, expire);

		/* PROPRIETARY */

		set(DDF_InstrumentField.DDF_EXPIRE_MONTH, newText(ddf_expire_month));
		set(DDF_InstrumentField.DDF_EXPIRE_YEAR, newText(ddf_expire_year));

		set(DDF_InstrumentField.DDF_ZONE, zone);
		set(DDF_InstrumentField.DDF_EXCHANGE, exchange);
		set(DDF_InstrumentField.DDF_EXCH_DESC, newText(exchangeComment));
		set(DDF_InstrumentField.DDF_SYMBOL_UNIVERSAL, newText(symbolReal));
		set(DDF_InstrumentField.DDF_SYMBOL_HISTORICAL, newText(symbolHist));
		set(DDF_InstrumentField.DDF_SYMBOL_REALTIME, newText(symbolDDFReal));

	}

}
