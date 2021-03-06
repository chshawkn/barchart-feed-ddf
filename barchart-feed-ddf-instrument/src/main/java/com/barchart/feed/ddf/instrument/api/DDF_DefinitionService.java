/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.instrument.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.barchart.feed.api.model.meta.Instrument;


/**
 * contract: instrument service should cache all previous requests.
 */
public interface DDF_DefinitionService {
	
	List<Instrument> lookup(CharSequence symbol);
	
	Map<CharSequence, List<Instrument>> lookup(
			Collection<? extends CharSequence> symbols);
	
}
