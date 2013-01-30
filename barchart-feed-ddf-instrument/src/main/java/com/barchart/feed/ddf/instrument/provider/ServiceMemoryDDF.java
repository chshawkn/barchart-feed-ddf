/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.instrument.provider;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.feed.ddf.instrument.api.DDF_DefinitionService;
import com.barchart.feed.ddf.instrument.api.DDF_Instrument;
import com.barchart.feed.inst.api.Instrument;
import com.barchart.feed.inst.api.InstrumentConst;
import com.barchart.feed.inst.api.InstrumentGUID;
import com.barchart.feed.inst.api.SymbologyContext;
import com.barchart.util.anno.ThreadSafe;

/**
 * keeps in memory cache.
 */
@ThreadSafe
public class ServiceMemoryDDF implements DDF_DefinitionService {

	static final Logger log = LoggerFactory.getLogger(ServiceMemoryDDF.class);

	private final ConcurrentMap<InstrumentGUID, Instrument> guidMap = 
			new ConcurrentHashMap<InstrumentGUID, Instrument>();
	
	private final SymbologyContext<CharSequence> remote = new RemoteSymbologyContextDDF(guidMap);
			
	/**
	 * Instantiates a new service memory ddf.
	 */
	public ServiceMemoryDDF() {
	}

//	/** make an upper case id */
//	private DDF_Instrument load(final TextValue symbol) {
//
//		final TextValue lookup = lookupFromSymbol(symbol);
//
//		return instrumentMap.get(lookup);
//
//	}
//
//	/** this will make 3 entries for futures and 1 entry for equities */
//	private void store(final DDF_Instrument instrument) {
//
//		/**
//		 * making assumption that first lookup of ESM0 will set symbol GUID per
//		 * DDF convention; that is ESM2020 in year 2011;
//		 * "if symbol expired, move forward"
//		 * 
//		 * this logic can overwrite previously defined symbol; say we are in
//		 * year 2011; say ESM0 was already defined for ESM2020 resolution; now
//		 * request comes for ESM2010; now ESM0 will resolve to ESM2010, and not
//		 * ESM2020
//		 * 
//		 * @author g-litchfield Removed mapping by DDF_SYMBOL_REALTIME because
//		 *         this is not always unique and was causing caching problems,
//		 *         specifically in KCK2 vs KCK02
//		 * 
//		 */
//
//		final TextValue symbolDDF =
//				instrument.get(DDF_SYMBOL_REALTIME).toUpperCase();
//		final TextValue symbolHIST =
//				instrument.get(DDF_SYMBOL_HISTORICAL).toUpperCase();
//		final TextValue symbolGUID =
//				instrument.get(DDF_SYMBOL_UNIVERSAL).toUpperCase();
//
//		ddfInstrumentMap.put(symbolDDF, instrument);
//
//		// hack for bats
//
//		if (symbolDDF.toString().contains(".BZ")) {
//			final TextValue lookup =
//					ValueBuilder.newText(symbolDDF.toString()
//							.replace(".BZ", ""));
//
//			instrumentMap.put(lookup, instrument);
//		}
//
//		instrumentMap.put(symbolHIST, instrument);
//		instrumentMap.put(symbolGUID, instrument);
//
//		log.debug("defined instrument={}", symbolGUID);
//
//	}
	
	@Override
	public Instrument lookup(final CharSequence symbol) {
		
		if(symbol == null || symbol.length() == 0) {
			return InstrumentConst.NULL_INSTRUMENT;
		}
		
		final InstrumentGUID guid = remote.lookup(symbol.toString().toUpperCase());
		
		if(guid.equals(InstrumentConst.NULL_GUID)) {
			return InstrumentConst.NULL_INSTRUMENT;
		}
		
		Instrument instrument = guidMap.get(guid);

		if (instrument == null) {
			return InstrumentConst.NULL_INSTRUMENT;
		}

		return new InstrumentDDF(instrument);
		
	}

	@Override
	public DDF_Instrument lookupDDF(final CharSequence symbol) {

		if (symbol == null || symbol.length() == 0) {
			return DDF_InstrumentProvider.NULL_INSTRUMENT;
		}

		final InstrumentGUID guid = remote.lookup(symbol.toString().toUpperCase());
		
		if(guid.equals(InstrumentConst.NULL_GUID)) {
			return DDF_InstrumentProvider.NULL_INSTRUMENT;
		}
		
		Instrument instrument = guidMap.get(guid);

		if (instrument == null) {
			return DDF_InstrumentProvider.NULL_INSTRUMENT;
		}

		return new InstrumentDDF(instrument);

	}
	
	@Override
	public Map<CharSequence, Instrument> lookup(Collection<? extends CharSequence> symbols) {
		
		if (symbols == null || symbols.size() == 0) {
			return null; // TODO ??????????
		}

		final Map<CharSequence, InstrumentGUID> gMap = remote.lookup(symbols);
				
		final Map<CharSequence, Instrument> instMap = 
				new HashMap<CharSequence, Instrument>();

		for (final CharSequence symbol : symbols) {
			instMap.put(symbol.toString(), guidMap.get(gMap.get(symbol.toString().toUpperCase())));
		}

		return instMap;
	}

	@Override
	public Map<CharSequence, DDF_Instrument> lookupDDF(final Collection<? extends CharSequence> symbolList) {

		if (symbolList == null || symbolList.size() == 0) {
			return DDF_InstrumentProvider.NULL_MAP;
		}

		final Map<CharSequence, InstrumentGUID> gMap = remote.lookup(symbolList);
				
		final Map<CharSequence, DDF_Instrument> instMap = 
				new HashMap<CharSequence, DDF_Instrument>();

		for (final CharSequence symbol : symbolList) {
			instMap.put(symbol.toString(), new InstrumentDDF(guidMap.get(
					gMap.get(symbol.toString().toUpperCase()))));
		}

		return instMap;

	}

	@Override
	public Future<Instrument> lookupAsync(CharSequence symbol) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<CharSequence, Future<Instrument>> lookupAsync(
			Collection<? extends CharSequence> symbols) {
		// TODO Auto-generated method stub
		return null;
	}

}
