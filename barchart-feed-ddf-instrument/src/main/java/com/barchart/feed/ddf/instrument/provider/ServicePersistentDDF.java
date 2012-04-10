/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.instrument.provider;

import static com.barchart.feed.ddf.symbol.provider.DDF_Symbology.*;

import com.barchart.feed.ddf.instrument.api.DDF_Instrument;
import com.barchart.util.anno.ThreadSafe;
import com.barchart.util.values.api.TextValue;

/** does batch symbol lookup; keeps file system cache */
@ThreadSafe
public class ServicePersistentDDF extends ServiceBasicDDF {

	public ServicePersistentDDF() {
	}

	@Override
	public final void clear() {
	}

	@Override
	public final DDF_Instrument lookup(final TextValue symbol) {

		if (CodecHelper.isEmpty(symbol)) {
			return DDF_InstrumentProvider.NULL_INSTRUMENT;
		}

		/** make an upper case id */
		final TextValue lookup = lookupFromSymbol(symbol);

		System.out.println("lookip ServicePerDDF = null for " + symbol.toString());
		
		return null;

	}

}
