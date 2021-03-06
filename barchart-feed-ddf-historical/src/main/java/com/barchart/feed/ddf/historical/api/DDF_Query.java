/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.historical.api;

import static com.barchart.feed.ddf.historical.enums.DDF_QueryType.*;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import com.barchart.feed.api.model.meta.Instrument;
import com.barchart.feed.ddf.historical.enums.DDF_QueryEodType;
import com.barchart.feed.ddf.historical.enums.DDF_QueryEodVolume;
import com.barchart.feed.ddf.historical.enums.DDF_QueryOrder;
import com.barchart.feed.ddf.historical.enums.DDF_QueryType;
import com.barchart.util.common.anno.Mutable;

// TODO: Auto-generated Javadoc
/**
 * ddf historical market data query builder.
 *
 * @param <E>
 *            the element type
 */
@Mutable
public final class DDF_Query<E extends DDF_Entry> {

	/**
	 * Instantiates a new dD f_ query.
	 *
	 * @param queryType
	 *            the query type
	 */
	public DDF_Query(final DDF_QueryType<E> queryType) {
		this.type = queryType;
	}

	/** The type. */
	public DDF_QueryType<E> type;

	/** The instrument. */
	public Instrument instrument;

	/**
	 * this parameter should be set to the desired START date/time for  the
	 * query; the result set will include records back to, AND including, this
	 * value.
	 */
	public DateTime timeStart;

	/**
	 * this parameter should be set to the desired END date/time for the  query;
	 * the result set will include records up to, but NOT including, this 
	 * value.
	 */
	public DateTime timeEnd;

	/** query result sort order. */
	public DDF_QueryOrder resultOrder = DDF_QueryOrder.ASCENDING;

	/** positive limit of records to return; zero for no limit. */
	public int maxRecords = 0;

	/**
	 * An optional interval can be specified in order to aggregate sets of 
	 * contiguous
	 * minute records (the number of minute records in each set is equal
	 * to the specified interval) into one record. If the interval is omitted, 
	 * then a 1 minute interval (no aggregation) will be the  default.
	 */
	public int groupBy = 1;

	/**
	 * If true, back-adjust continuation chart data.
	 *
	 * Explanation: http://www.premiumdata.net/support/futurescontinuous2.php
	 */
	public boolean backadjust = false;

	/**
	 * Number of days before expiration to roll contracts for continuation
	 * charts.
	 */
	public int daystoexpiration = 0;

	/**
	 * contractroll: for futures, multi-contract nearest queries (data parameter
	 * set to dailynearest, weeklynearest, monthlynearest, quarterlynearest or
	 * yearlynearest), this parameter can be set to one of two values
	 * (expiration, combined), and determines how the switch from one contract
	 * to the next in the series is calculated. When the value specified is
	 * 'expiration', the switch from one contract to the next in the series will
	 * be based on the expiration date (and the value of the 'daystoexpiration'
	 * parameter if specified). When the value specified is 'combined', a
	 * combination of volume and openinterest will be used to determine when to
	 * switch from one contract to the next in the series (when using this
	 * value, the 'daystoexpiration' parameter is ignored).
	 */
	public String contractroll;

	/** The eod type. */
	public DDF_QueryEodType eodType;

	/** The eod volume. */
	public DDF_QueryEodVolume eodVolume;

	private final CharSequence renderId() {
		return instrument == null ? "NONE" : instrument.symbol();
	}

	private final CharSequence renderDescription() {
		return instrument == null ? "NONE" : instrument.description();
	}

	private final static DateTime NULL_TIME = new DateTime(0, DateTimeZone.UTC);

	private final CharSequence renderTime(/* local */DateTime time) {
		if (time == null) {
			time = NULL_TIME;
		}
		if (instrument == null) {
			return time.toString();
		} else {
			final DateTimeZone zone = DateTimeZone.forOffsetMillis((int) (instrument.timeZoneOffset()));
			return time.withZone(zone).toString();
		}
	}

	private final CharSequence renderOrder(/* local */DDF_QueryOrder order) {
		if (order == null) {
			order = DDF_QueryOrder.ASCENDING;
		}
		return order.toString();
	}

	private final CharSequence renderEodType(/* local */DDF_QueryEodType type) {
		if (type == null) {
			type = DDF_QueryEodType.DAILY;
		}
		return type.toString();
	}

	private final CharSequence renderEodVolume(/* local */DDF_QueryEodVolume volume) {
		if (volume == null) {
			volume = DDF_QueryEodVolume.CONTRACT;
		}
		return volume.toString();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#toString()
	 */
	@Override
	public final String toString() {
		return "" + //
				"\n queryType   " + type + //
				"\n instrument  " + renderId() + //
				"\n timeStart   " + timeStart + //
				"\n timeEnd     " + timeEnd + //
				"\n resultOrder " + resultOrder + //
				"\n maxRecords  " + maxRecords + //
				"\n groupBy     " + groupBy + //
				"\n eodType     " + eodType + //
				"\n eodVolume   " + eodVolume + //
				"";
	}

	/**
	 * Description.
	 *
	 * @return the string
	 */
	public final String description() {
		final StringBuilder text = new StringBuilder(128);
		build: {
			if (type == null) {
				text.append("<invalid query>");
				break build;
			}
			//
			text.append(type);
			text.append(" ");
			text.append(renderId());
			text.append("(");
			text.append(renderDescription());
			text.append(")");
			text.append(" from ");
			text.append(renderTime(timeStart));
			text.append(" upto ");
			text.append(renderTime(timeEnd));
			text.append(" order ");
			text.append(renderOrder(resultOrder));
			//
			if (type.is(TICKS)) {
				break build;
			}
			if (type.isIn(MINUTES, MINUTES_NEARBY, MINUTES_FORM_T)) {
				text.append(" group ");
				text.append(groupBy);
				break build;
			}
			if (type.is(END_OF_DAY) && instrument != null && instrument.CFICode().charAt(0) == 'F') {
				text.append(" eodType ");
				text.append(renderEodType(eodType));
				text.append(" eodVolume ");
				text.append(renderEodVolume(eodVolume));
				break build;
			}
		}
		return text.toString();
	}

	/**
	 * Inits the from.
	 *
	 * @param that
	 *            the that
	 */
	public final void initFrom(final DDF_Query<E> that) {
		this.type = that.type;
		this.instrument = that.instrument;
		this.timeStart = that.timeStart;
		this.timeEnd = that.timeEnd;
		this.groupBy = that.groupBy;
		this.maxRecords = that.maxRecords;
		this.eodType = that.eodType;
		this.eodVolume = that.eodVolume;
	}

}
