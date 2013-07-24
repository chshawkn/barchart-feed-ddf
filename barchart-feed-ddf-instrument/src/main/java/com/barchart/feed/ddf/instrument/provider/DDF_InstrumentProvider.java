package com.barchart.feed.ddf.instrument.provider;

import static com.barchart.feed.ddf.util.HelperXML.XML_STOP;
import static com.barchart.feed.ddf.util.HelperXML.xmlFirstChild;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.joda.time.DateTime;
import org.openfeed.proto.inst.InstrumentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.barchart.feed.api.model.meta.Instrument;
import com.barchart.feed.api.util.Observer;
import com.barchart.feed.ddf.symbol.enums.DDF_ExpireMonth;
import com.barchart.feed.ddf.util.HelperXML;
import com.barchart.feed.inst.InstrumentDefinitionResult;
import com.barchart.feed.inst.participant.InstrumentState;
import com.barchart.feed.inst.provider.InstrumentFactory;
import com.barchart.feed.inst.provider.InstrumentMap;

public final class DDF_InstrumentProvider {
	
	private static final long DEFAULT_TIMEOUT = 5000;
	private static final TimeUnit MILLIS = TimeUnit.MILLISECONDS;
	private static final int MAX_URL_LEN = 7500;
	private static final long REMOTE_LOOKUP_INTERVAL = 1000;
	
	private static final Logger log = LoggerFactory
			.getLogger(DDF_InstrumentProvider.class);
	
	private static final ConcurrentMap<String, InstrumentState> symbolMap =
			new ConcurrentHashMap<String, InstrumentState>();
	
	private static final ArrayBlockingQueue<String> remoteQueue = 
			new ArrayBlockingQueue<String>(1000 * 1000);
	
	private static final List<String> failedRemoteQueue =
			new CopyOnWriteArrayList<String>();
	
	private static volatile InstrumentMap db = InstrumentMap.NULL;
	
	private DDF_InstrumentProvider() {
		
	}
	
	/**
	 * Default executor service with dameon threads
	 */
	private volatile static ExecutorService executor = Executors.newCachedThreadPool( 
			
			new ThreadFactory() {

		final AtomicLong counter = new AtomicLong(0);
		
		@Override
		public Thread newThread(final Runnable r) {
			
			final Thread t = new Thread(r, "Feed thread " + 
					counter.getAndIncrement()); 
			
			t.setDaemon(true);
			
			return t;
		}
		
	});
	
	static {
		executor.submit(new RemoteRunner());
	}
	
	/**
	 * Bind framework executor.
	 * @param e
	 */
	public synchronized static void bindExecutorService(final ExecutorService e) {
		executor.shutdownNow();
		executor = e;
		executor.submit(new RemoteRunner());
	}
	
	/**
	 * @param map Bind an already built db map
	 */
	public synchronized static void bindDatabaseMap(final InstrumentMap map) {
		db = map;
	}
	
	/**
	 * This takes an instrument stub from a feed message, and does several things.
	 * 
	 * 1. Checks symbol against map.  If the stub represents an instrument already
	 * in the system, then it returns the canonical reference to that instrument.
	 * 
	 * 2. If the symbol is unknown, it will make a new InstrumentState from the info
	 * in the stub and begin an async lookup of info.
	 * 
	 * @param inst
	 * @return
	 */
	public static Instrument fromMessage(final Instrument inst) {
		
		if(inst == null || inst.isNull()) {
			return Instrument.NULL;
		}
		
		/* NOTE id() in ddf is just the realtime symbol, not an actual GUID */
		final String symbol = formatSymbol(inst.id().toString());
		
		if(symbolMap.containsKey(symbol)) {
			return symbolMap.get(symbol);
		}
		
		/* New symbol, create stub */
		final InstrumentState instState = InstrumentStateFactory.
				newInstrumentFromStub(inst);
		
		symbolMap.put(symbol, instState);
		log.debug("Put {} stub into map", symbol);
		
		/* Asnyc lookup */
		executor.submit(populateRunner(symbol));
		
		return instState;
		
	}
	
	public static Instrument fromSymbol(String symbol) {
		
		if(symbol == null || symbol.isEmpty()) {
			return Instrument.NULL;
		}
		
		symbol = formatSymbol(symbol);
		
		if(symbolMap.containsKey(symbol)) {
			return symbolMap.get(symbol);
		}
		
		final InstrumentState instState = InstrumentStateFactory.
				newInstrument(symbol);
		
		symbolMap.put(symbol, instState);
		
		/* Asnyc lookup */
		try {
			remoteQueue.put(symbol);
		} catch (final Exception e) {
			failedRemoteQueue.add(symbol);
		}
		//executor.submit(populateRunner(symbol));
		
		return instState;
		
	} 
	
	public static Map<String, Instrument> fromSymbols(
			final Collection<String> symbols) {
		
		final Map<String, Instrument> map = new HashMap<String, Instrument>();
		
		for(final String symbol : symbols) {
			map.put(symbol, fromSymbol(symbol));
		}
		
		return map;
		
	}

	public static Instrument fromHistorical(String symbol) {
		
		if(symbol == null || symbol.isEmpty()) {
			return Instrument.NULL;
		}
		
		symbol = formatHistoricalSymbol(symbol);
		
		if(symbolMap.containsKey(symbol)) {
			return symbolMap.get(symbol);
		}
		
		final InstrumentState instState = InstrumentStateFactory.
				newInstrument(symbol);
		
		symbolMap.put(symbol, instState);
		
		/* Asnyc lookup */
		executor.submit(populateRunner(symbol));
		
		return instState;
		
	}
	
	private static Observer<InstrumentDefinitionResult> observer = 
			new Observer<InstrumentDefinitionResult>() {

		@Override
		public void onNext(final InstrumentDefinitionResult result) {
			
			final String symbol = result.expression();
			
			/* If exception, add to failed */
			if(result.exception() != null) {
				failedRemoteQueue.add(symbol);
			}
			
			final InstrumentDefinition def = result.result();
			
			if(def == InstrumentDefinition.getDefaultInstance()) {
				log.warn("Instrument result was empty for {}", symbol);
				return;  // Ignore
			}
			
			final InstrumentState iState = symbolMap.get(symbol);
			
			if(iState.isNull()) {
				symbolMap.put(symbol, InstrumentFactory.instrumentState(result.result()));
			} else {
				log.debug("Processing {}", result.result().toString());
				iState.process(result.result());
			}
			
		}
		
	};
	
	private static class InstDefResult implements InstrumentDefinitionResult {

		private final String symbol;
		private final InstrumentDefinition def;
		private final Throwable t;
		
		InstDefResult(final String symbol, final InstrumentDefinition def) {
			this.symbol = symbol;
			this.def = def;
			t = null;
		}
		
		InstDefResult(final String symbol, final Throwable t) {
			this.symbol = symbol;
			def = InstrumentDefinition.getDefaultInstance();
			this.t = t;
		}
		
		@Override
		public InstrumentDefinition result() {
			return def;
		}

		@Override
		public String expression() {
			return symbol;
		}

		@Override
		public Throwable exception() {
			return t;
		}
		
	}
	
	private static Runnable populateRunner(final String id) {
		
		return new Runnable() {

			@Override
			public void run() {
				
				try {
				
					// Should already have an instState in map, this just updates
					InstrumentState iState = symbolMap.get(id);
					
					if(iState == null) {
						log.error("Runner called for {}, expected stub missing", id);
						return;
					}
					
					InstrumentDefinition inst;
					
					inst = db.get(id);
					
					if(inst != null && inst != InstrumentDefinition.getDefaultInstance()) {
						
						observer.onNext(new InstDefResult(id, inst));
						
						log.debug("Instrument def found in db for {}", id);
						
						return;
					}
					
					/* Remote lookup */
					final Future<InstrumentDefinition> futdef = 
							executor.submit(remoteSingle(id));
					
					/* Runnable blocks here, waiting for remote */
					inst = futdef.get(DEFAULT_TIMEOUT, MILLIS);
					
					if(inst != null) {
						
						/*
						 * This updates all references to the instrument
						 */
						iState.process(inst);
						
						log.debug("Instrument def found in remote for {}", id);
						
						return;
					}
					
					// Here inst was null, so some error needs to be handled
					
				} catch (final Throwable t) {
					log.error("Exception in runner {}", t);
					observer.onNext(new InstDefResult(id, t));
				}
				
			}
			
		};
		
	}
	
	private static Runnable populateMulti(final List<String> symbols) {
		
		return new Runnable() {

			@Override
			public void run() {
				
				final List<String> toLookup = new ArrayList<String>();
				
				for(final String symbol : symbols) {
					
					if(local(symbol)) {
						toLookup.add(symbol);
					}
					
				}
				
				
				
			}
			
		};
		
	}
	
	private static boolean local(final String symbol) {
		
		InstrumentState iState = symbolMap.get(symbol);
		
		if(iState == null) {
			log.error("Runner called for {}, expected stub missing", symbol);
			return false;
		}
		
		InstrumentDefinition inst = db.get(symbol);
		
		if(inst != null && inst != InstrumentDefinition.getDefaultInstance()) {
			observer.onNext(new InstDefResult(symbol, inst));
			return false;
		}
		
		return true;
	}
	
	private static final String SERVER_EXTRAS = "extras.ddfplus.com";

	private static final String urlInstrumentLookup(final CharSequence lookup) {
		return "http://" + SERVER_EXTRAS + "/instruments/?lookup=" + lookup;
	}
	
	static Callable<InstrumentDefinition> remoteSingle(final String lookup) {
		
		return new Callable<InstrumentDefinition>() {
	
			@Override
			public InstrumentDefinition call() throws Exception {
				
				try {
					
					log.debug("Starting remote lookup for {}", lookup);
					
					final String symbolURI = urlInstrumentLookup(lookup);
					final Element root = HelperXML.xmlDocumentDecode(symbolURI);
					final Element tag = xmlFirstChild(root, "instrument", XML_STOP);
					final InstrumentDefinition instDOM = InstrumentXML.decodeXML(tag);
					
					if(instDOM == null || instDOM == InstrumentDefinition.getDefaultInstance()) {
						log.warn("Empty instrument def returned from remote lookup: {}", lookup);
						failedRemoteQueue.add(lookup);
						return InstrumentDefinition.getDefaultInstance();
					}
					
					return instDOM;
					
				} catch (final Throwable t) {
					failedRemoteQueue.add(lookup);
					log.error("Remote instrument lookup callable exception: {}", t);
					return InstrumentDefinition.getDefaultInstance();
				}
				
			}
		
		};
		
	}
	
	static Callable<Map<String, InstrumentDefinition>> remoteBatch(final String symbols) {
		
		return new Callable<Map<String, InstrumentDefinition>>() {

			@Override
			public Map<String, InstrumentDefinition> call() throws Exception {
				
				try {
					
					final Map<String, InstrumentDefinition> defs = 
							new HashMap<String, InstrumentDefinition>();
					
					log.debug("remote batch on {}", urlInstrumentLookup(symbols));
					
					final URL url = new URL(urlInstrumentLookup(symbols));
					final InputStream input = url.openStream();
					final BufferedInputStream stream =
							new BufferedInputStream(input);

					final SAXParserFactory factory =
							SAXParserFactory.newInstance();
					final SAXParser parser = factory.newSAXParser();
					
					final DefaultHandler handler = new DefaultHandler() {
						
						InstrumentDefinition def;
						
						@Override
						public void startElement(final String uri,
								final String localName, final String qName,
								final Attributes ats) throws SAXException {
							
							if (qName != null && qName.equals("instrument")) {

								try {
									def = InstrumentXML.decodeSAX(ats);
									if (def != InstrumentDefinition.getDefaultInstance()) {
										defs.put(def.getSymbol(), def);
									}
								} catch (final SymbolNotFoundException se) {
									observer.onNext(new InstDefResult(se.getMessage(), se));
								} catch (final Exception e) {
									
								}
							}
							
						}
						
					};
					
					parser.parse(stream, handler);
					
					return defs;
					
				} catch (final Throwable t) {
					failedRemoteQueue.addAll(Arrays.asList(symbols.split(",")));
					return null;
				}
				
			}
			
		};
		
	}
	
	static class RemoteRunner implements Runnable {
		
		private List<Future<Map<String, InstrumentDefinition>>> futures = 
				new ArrayList<Future<Map<String, InstrumentDefinition>>>();
		
		private final List<Callable<Map<String, InstrumentDefinition>>> callables = 
				new ArrayList<Callable<Map<String, InstrumentDefinition>>>();
		
		@Override
		public void run() {
			
			try {
				
				while(!Thread.interrupted()) {
					
					Thread.sleep(REMOTE_LOOKUP_INTERVAL);
					
					while(!remoteQueue.isEmpty()) {
						callables.add(remoteBatch(buildQuerey()));
					}
					
					futures = executor.invokeAll(callables, DEFAULT_TIMEOUT, MILLIS);
					
					for(final Future<Map<String, InstrumentDefinition>> f : futures) {
						
						for(final Entry<String, InstrumentDefinition> e : f.get().entrySet()) {
							
							final InstrumentDefinition def = e.getValue();
							
							if(def == null || def == InstrumentDefinition.getDefaultInstance()) {
								observer.onNext(new InstDefResult(e.getKey(),
										new Throwable("Could not find " + e.getKey())));
							} else {
								observer.onNext(new InstDefResult(e.getKey(), def));
							}
							
						}
						
					}
					
					futures.clear();
					callables.clear();
					
				}
				
			} catch (final Throwable t) {
				log.error("Exception in Remote Runner Thread", t);
			}
			
		}
		
	}
	
	private static String buildQuerey() throws Exception {
		
		final StringBuilder sb = new StringBuilder();
		
		int len = 0;
		int symCount = 0;
		
		while(len < MAX_URL_LEN && symCount < 400 && !remoteQueue.isEmpty()) {
			
			final String s = remoteQueue.take();
			
			log.debug("Pulled {} from remote queue", s);
			
			symCount++;
			len += s.length() + 1;
			sb.append(s).append(",");
			
		}
		
		log.debug("Sending {} to remote lookup", sb.toString());
		
		return sb.toString();
		
	}
	
	private static final int YEAR;
	private static final char MONTH;
	
	static {
		final DateTime now = new DateTime();
		YEAR = now.year().get();
		MONTH = DDF_ExpireMonth.fromDateTime(now).code;
	}
	
	/* ***** ***** Symbol Formatting ***** ***** */
	
	private static final char[] T_Z_O = new char[] {'2', '0', '1'};
	private static final char[] T_Z_T = new char[] {'2', '0', '2'};
	private static final char[] T_Z = new char[] {'2', '0'};
	private static final char[] O = new char[] {'1'};
	
	public static String formatSymbol(String symbol) {
		
		if(symbol == null) {
			return "";
		}
		
		final int len = symbol.length();
		
		if(len < 3) {
			return symbol;
		}
		
		/* Spread */
		if(symbol.charAt(0) == '_') {
			return symbol;
		}
		
		/* Option */
		if(symbol.matches(".+\\d(C|P|D|Q)$")) {
			
			
			int pIndex = len - 2;
			while(Character.isDigit(symbol.charAt(pIndex))) {
				
				if(pIndex <= 2) {
					log.error("Failed to format option {}", symbol);
					return symbol + " failed";
				}
				
				pIndex--;
			}

			final String price = symbol.substring(pIndex + 1, len-1);
			
			final char mon = symbol.charAt(pIndex);
			DDF_ExpireMonth sMon = DDF_ExpireMonth.fromCode(mon);
			DDF_ExpireMonth nowMon = DDF_ExpireMonth.fromCode(MONTH);
			final String y = (sMon.value >= nowMon.value) ? String.valueOf(YEAR) :
				String.valueOf(YEAR+1);
			
			final StringBuilder sb = new StringBuilder();
			
			sb.append(symbol.substring(0, pIndex + 1)); // prefix
			sb.append(y); // year
			sb.append("|"); // pipe
			sb.append(price);
			
			if(symbol.matches(".+(C|D)$")) {
				return sb.append("C").toString();
			} else {
				return sb.append("P").toString();
			}
			
		}
		
		/* e.g. GOOG */
		if(!Character.isDigit(symbol.charAt(len - 1))) {
			return symbol;
		}
		
		/* e.g. ESH3 */
		if(!Character.isDigit(symbol.charAt(len - 2))) {
			
			final StringBuilder sb = new StringBuilder(symbol);
			int last = Character.getNumericValue(symbol.charAt(len - 1));
			if(YEAR % 2010 < last) {
				return sb.insert(len - 1, T_Z_O).toString();
			} else if(YEAR % 2010 > last) {
				return sb.insert(len - 1, T_Z_T).toString();
			} else {
				if(symbol.charAt(len - 2) >= MONTH) {
					return sb.insert(len - 1, T_Z_O).toString();
				} else {
					return sb.insert(len - 1, T_Z_T).toString();
				}
			}
			
		}
		
		/* e.g. ESH13 */
		if(!Character.isDigit(symbol.charAt(len - 3))) {
			return new StringBuilder(symbol).insert(len-2, T_Z).toString();
		}
		
		return symbol;
		
	}

	public static String formatHistoricalSymbol(String symbol) {
		
		if(symbol == null) {
			return "";
		}
		
		if(symbol.length() < 3) {
			return symbol;
		}
		
		/* e.g. GOOG */
		if(!Character.isDigit(symbol.charAt(symbol.length() - 1))) {
			return symbol;
		}
		
		/* e.g. ESH3 */
		if(!Character.isDigit(symbol.charAt(symbol.length() - 2))) {
			return new StringBuilder(symbol).insert(symbol.length() - 1, O).toString();
		}
		
		return symbol;
	}

}
