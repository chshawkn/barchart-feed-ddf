package com.barchart.feed.ddf.instrument.provider;

import static com.barchart.feed.ddf.instrument.provider.XmlTagExtras.LOOKUP;
import static com.barchart.feed.ddf.util.HelperXML.XML_STOP;
import static com.barchart.feed.ddf.util.HelperXML.xmlFirstChild;
import static com.barchart.feed.ddf.util.HelperXML.xmlStringDecode;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import rx.Observable;
import rx.subjects.ReplaySubject;

import com.barchart.feed.api.consumer.MetadataService.Result;
import com.barchart.feed.api.consumer.MetadataService.SearchContext;
import com.barchart.feed.api.model.meta.Instrument;
import com.barchart.feed.api.model.meta.id.InstrumentID;
import com.barchart.feed.api.model.meta.id.VendorID;
import com.barchart.feed.base.provider.Symbology;
import com.barchart.feed.ddf.instrument.provider.DDF_FeedInstProvider.RemoteRunner;
import com.barchart.feed.ddf.util.HelperXML;

public class DDF_RxInstrumentProvider {

	private static final Logger log = LoggerFactory.getLogger(
			DDF_RxInstrumentProvider.class);

	private static final int MAX_URL_LEN = 7500;

	static final ConcurrentMap<String, List<InstrumentState>> symbolMap =
			new ConcurrentHashMap<String, List<InstrumentState>>();

	static final ConcurrentMap<InstrumentID, InstrumentState> idMap =
			new ConcurrentHashMap<InstrumentID, InstrumentState>();

	/* ***** ***** ***** Begin Executor ***** ***** ***** */

	/**
	 * Default executor service with daemon threads
	 */
	private volatile static ExecutorService executor = Executors.newCachedThreadPool(

			new ThreadFactory() {

		final AtomicLong counter = new AtomicLong(0);

		@Override
		public Thread newThread(final Runnable r) {

			final Thread t = new Thread(r, "Feed thread " + counter.getAndIncrement());

			t.setDaemon(true);

			return t;
		}

	});

	/**
	 * Bind framework executor.
	 * @param e
	 */
	public synchronized static void bindExecutorService(final ExecutorService e) {

		log.debug("Binding new executor service");

		executor.shutdownNow();
		executor = e;
		executor.submit(new RemoteRunner());

	}

	/* ***** ***** ***** Begin ID Lookup ***** ***** ***** */

	public static Observable<Map<InstrumentID, Instrument>> fromID(final InstrumentID... ids) {
		
		final ReplaySubject<Map<InstrumentID, Instrument>> sub = ReplaySubject.create();
		executor.submit(runnableFromIDs(sub, ids));
		
		return sub;
	}
	
	public static Runnable runnableFromIDs(final ReplaySubject<Map<InstrumentID, Instrument>> sub, 
			final InstrumentID... ids) {
		
		return new Runnable() {

			@Override
			public void run() {
				
				final Map<InstrumentID, Instrument> res = new HashMap<InstrumentID, Instrument>();

				final List<InstrumentID> toBatch = new ArrayList<InstrumentID>();

				/* Filter out cached symbols */
				for(final InstrumentID id : ids) {

					if(id == null) {
						continue;
					}

					if(idMap.containsKey(id)) {
						res.put(id, idMap.get(id));
					} else {
						toBatch.add(id);
					}

				}

				try {

					final List<String> queries = buildIDQueries(toBatch);

					for(final String query : queries) {

						final Map<InstrumentID, InstrumentState> lookup = remoteIDLookup(query);

						/* Store instruments returned from lookup */
						for(final Entry<InstrumentID, InstrumentState> e : lookup.entrySet()) {
							
							final InstrumentID id = e.getKey();
							final InstrumentState inst = e.getValue();
							
							if(inst == null || inst.isNull()) {
								continue;
							}
							
							final String sym = inst.symbol();
							
							idMap.put(id, e.getValue());

							if(!symbolMap.containsKey(sym)) {
								symbolMap.put(sym, new ArrayList<InstrumentState>());
								symbolMap.get(sym).add(inst);
							}

							/* Add alternate options symbol */
							if(sym.contains("|")) {
								final String alt = inst.vendorSymbols().get(VendorID.BARCHART_SHORT);
								symbolMap.put(alt, new ArrayList<InstrumentState>());
								symbolMap.get(alt).add(inst);
							}


							/* Match up symbols to user entered symbols and store them in the final result */
							res.put(id,  inst);

						}

						/*
						 * Populate symbols for which nothing was returned, guarantee every symbol
						 * requested is in map returned
						 */
						for (final InstrumentID i : ids) {

							if (!res.containsKey(i)) {
								res.put(i, InstrumentState.NULL);
							}

						}

					}

					sub.onNext(res);
					sub.onCompleted();
				} catch (final Exception e1) {
					sub.onError(e1);
				}
				
			}
			
		};
	}

	/* ***** ***** ***** Begin String Search ***** ***** ***** */

	public static Observable<Result<Instrument>> fromString(final String... symbols) {
		return fromString(SearchContext.NULL, symbols);
	}

	public static Observable<Result<Instrument>> fromString(final SearchContext ctx,
			final String... symbols) {

		final ReplaySubject<Result<Instrument>> sub = ReplaySubject.create();
		executor.submit(runnableFromString(sub, ctx, symbols));

		return sub;
	}

	public static Runnable runnableFromString(
			final ReplaySubject<Result<Instrument>> sub,
			final SearchContext ctx, 
			final String... symbols) {

		return new Runnable() {

			@Override
			public void run() {

				final Map<String, List<InstrumentState>> res =
						new HashMap<String, List<InstrumentState>>();

				final List<String> toBatch = new ArrayList<String>();
				final Map<String, String> userSymbols = new HashMap<String, String>();

				/* Filter out cached symbols */
				for(final String symbol : symbols) {

					if(symbol == null) {
						continue;
					}

					final String formattedSymbol = Symbology.formatSymbol(symbol);

					if (symbolMap.containsKey(formattedSymbol)) {
						res.put(symbol, symbolMap.get(formattedSymbol));
					} else {
						toBatch.add(formattedSymbol);
						userSymbols.put(symbol, formattedSymbol);
					}

				}

				try {

					final List<String> queries = buildSymbolQueries(toBatch);

					for(final String query : queries) {

						final Map<String, List<InstrumentState>> lookup = remoteSymbolLookup(query);

						/* Store instruments returned from lookup */
						for(final Entry<String, List<InstrumentState>> e : lookup.entrySet()) {
							symbolMap.put(e.getKey(), e.getValue());

							if(!e.getValue().isEmpty()) {
								final InstrumentState i = e.getValue().get(0);
								idMap.put(i.id(), i);
								symbolMap.put(i.symbol(), e.getValue()); 
							}

							/* Add alternate options symbol */
							if(!e.getValue().isEmpty()) {
								final InstrumentState inst = e.getValue().get(0);
								if(inst != null) {

									if(inst.symbol().contains("|")) {
										symbolMap.put(inst.vendorSymbols().get(VendorID.BARCHART_SHORT), e.getValue());
									}

								}
							}

							/* Match up symbols to user entered symbols and store them in the final result */
							for (final Map.Entry<String, String> en : userSymbols.entrySet()) {

								if (en.getValue().equals(e.getKey())) {
									res.put(en.getKey(), e.getValue());
								}

							}

						}

						/*
						 * Populate symbols for which nothing was returned, guarantee every symbol
						 * requested is in map returned
						 */
						for (final Map.Entry<String, String> en : userSymbols.entrySet()) {

							if (!res.containsKey(en.getKey())) {
								res.put(en.getKey(), Collections.<InstrumentState> emptyList());
							}

						}

					}

					sub.onNext(result(res));
					sub.onCompleted();
				} catch (final Exception e1) {
					log.error("Exception in inst lookup runnable", e1);
					sub.onError(e1);
				}
			}

		};

	}

	static Map<String, List<InstrumentState>> remoteSymbolLookup(final String query) {

		try {

			final Map<String, List<InstrumentState>> result = new HashMap<String, List<InstrumentState>>();

			// log.debug("remote batch on {}", urlSymbolLookup(query));

			final URL url = new URL(urlSymbolLookup(query));

			final HttpURLConnection connection = (HttpURLConnection) url.openConnection();

			connection.setRequestProperty("Accept-Encoding", "gzip");

			connection.connect();

			InputStream input = connection.getInputStream();

			if (connection.getContentEncoding() != null && connection.getContentEncoding().equals("gzip")) {
				input = new GZIPInputStream(input);
			}

			final BufferedInputStream stream = new BufferedInputStream(input);

			final SAXParserFactory factory = SAXParserFactory.newInstance();
			final SAXParser parser = factory.newSAXParser();
			final DefaultHandler handler = symbolHandler(result);

			parser.parse(stream, handler);

			return result;

		} catch (final Exception e) {
			log.error("Exception on remote symbol lookup  -  {}", query);
			throw new RuntimeException(e);
		}

	}
	
	static Map<InstrumentID, InstrumentState> remoteIDLookup(final String query) {
		
		try {

			final Map<InstrumentID, InstrumentState> result = new HashMap<InstrumentID, InstrumentState>();

			// log.debug("remote batch on {}", urlIDLookup(query));

			final URL url = new URL(urlIDLookup(query));

			final HttpURLConnection connection = (HttpURLConnection) url.openConnection();

			connection.setRequestProperty("Accept-Encoding", "gzip");

			connection.connect();

			InputStream input = connection.getInputStream();

			if (connection.getContentEncoding() != null && connection.getContentEncoding().equals("gzip")) {
				input = new GZIPInputStream(input);
			}

			final BufferedInputStream stream = new BufferedInputStream(input);

			final SAXParserFactory factory = SAXParserFactory.newInstance();
			final SAXParser parser = factory.newSAXParser();
			final DefaultHandler handler = idHandler(result);

			parser.parse(stream, handler);

			return result;

		} catch (final Exception e) {
			log.error("Exception on remote ID lookup  -  {}", query, e);
			throw new RuntimeException(e);
		}
		
	}

	public static DefaultHandler symbolHandler(final Map<String, List<InstrumentState>> result) {
		return new DefaultHandler() {

			private String lookup = null;
			private Attributes atts = null;
			private List<Attributes> vendors = new ArrayList<Attributes>();
			
			@Override
			public void startElement(
					final String uri, 
					final String localName, 
					final String qName,	
					final Attributes ats) throws SAXException {

				if ("instrument".equals(qName)) {
					
					/* Check if we need to make a new instrument object */
					if(atts != null) {
						try {
							result.put(lookup, Arrays.<InstrumentState> asList(new DDF_Instrument(atts, vendors)));
						} catch (final SymbolNotFoundException se) {
							result.put(lookup, Collections.<InstrumentState> emptyList());
						} catch (final Exception e) {
							e.printStackTrace();
						}
						
						vendors = new ArrayList<Attributes>();
					}
					
					atts = new AttributesImpl(ats);
					lookup = xmlStringDecode(ats, LOOKUP, XML_STOP);
					
				} else if("ticker".equals(qName)) {
					vendors.add(new AttributesImpl(ats));
				}

			}
			
			@Override
			public void endDocument() {
				
				if(atts != null) {
					try {
						result.put(lookup, Arrays.<InstrumentState> asList(new DDF_Instrument(atts, vendors)));
					} catch (final SymbolNotFoundException se) {
						result.put(lookup, Collections.<InstrumentState> emptyList());
					} catch (final Exception e) {
						throw new RuntimeException(e);
					}
				}
				
			}

		};
	}
	
	protected static DefaultHandler idHandler(final Map<InstrumentID, InstrumentState> result) {
		return new DefaultHandler() {

			private InstrumentID lookup = null;
			private Attributes atts = null;
			private List<Attributes> vendors = new ArrayList<Attributes>();
			
			@Override
			public void startElement(
					final String uri, 
					final String localName, 
					final String qName,	
					final Attributes ats) throws SAXException {

				if ("instrument".equals(qName)) {
					
					/* Check if we need to make a new instrument object */
					if(atts != null) {
						try {
							result.put(lookup, new DDF_Instrument(atts, vendors));
						} catch (final SymbolNotFoundException se) {
							result.put(lookup, InstrumentState.NULL);
						} catch (final Exception e) {
							e.printStackTrace();
						}
						
						vendors = new ArrayList<Attributes>();
					}
					
					atts = new AttributesImpl(ats);
					// TODO Review 
					lookup = new InstrumentID(xmlStringDecode(ats, XmlTagExtras.ID, XML_STOP));
					
				} else if("ticker".equals(qName)) {
					vendors.add(new AttributesImpl(ats));
				}

			}
			
			@Override
			public void endDocument() {
				
				if(atts != null) {
					try {
						result.put(lookup, new DDF_Instrument(atts, vendors));
					} catch (final SymbolNotFoundException se) {
						result.put(lookup, InstrumentState.NULL);
					} catch (final Exception e) {
						throw new RuntimeException(e);
					}
				}
				
			}

		};
	}
	

	/* ***** ***** ***** CQG ***** ***** ***** */

	private static final ConcurrentMap<String, String> cqgSymMap =
			new ConcurrentHashMap<String, String>();

	public static Observable<String> fromCQGString(final String symbol) {
		try {
			return Observable.from(executor.submit(callableFromCQGString(SearchContext.NULL, symbol)));
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static Callable<String> callableFromCQGString(
			final SearchContext ctx, final String symbol) {

		return new Callable<String>() {

			@Override
			public String call() throws Exception {

				/* Filter out cached symbols */
				if(cqgSymMap.containsKey(symbol)) {
					return cqgSymMap.get(symbol);
				}

				final String query = cqgInstLoopURL(symbol);
				
				log.debug(query);

				final Element root = HelperXML.xmlDocumentDecode(query);

		        final Element tag = xmlFirstChild(root, "symbol", XML_STOP);

		        final String result = Symbology.formatSymbol(tag.getTextContent());

		        if(result != null) {
		        	cqgSymMap.put(symbol, result);
		        }

		        return result;

			}
		};

	}

	private static Result<Instrument> result(final Map<String, List<InstrumentState>> res) {

		return new Result<Instrument>() {

			@Override
			public SearchContext context() {
				return SearchContext.NULL;
			}

			@Override
			public Map<String, List<Instrument>> results() {
				final Map<String, List<Instrument>> result = new HashMap<String, List<Instrument>>();
				for(final Entry<String, List<InstrumentState>> e : res.entrySet()) {
					final List<Instrument> list = new ArrayList<Instrument>();
					if(!e.getValue().isEmpty()) {
						list.add(e.getValue().get(0));
					}
					result.put(e.getKey(), list);
				}
				return result;
			}

			@Override
			public boolean isNull() {
				return false;
			}
		};

	}

	/* ***** ***** ***** Begin Remote Lookup ***** ***** ***** */

	static final String SERVER_EXTRAS = "extras.ddfplus.com";
	
	static String lookupParams = "";
	
	private static final String ALL_VENDORS = "&expanded=1";

	static final String urlSymbolLookup(final CharSequence lookup) {
		return "http://" + SERVER_EXTRAS + "/instruments/?lookup=" + lookup + ALL_VENDORS + lookupParams;
	}
	
	static final String urlIDLookup(final CharSequence lookup) {
		return "http://" + SERVER_EXTRAS + "/instruments/?id=" + lookup + ALL_VENDORS;
	}

	private static final String cqgInstLoopURL(final CharSequence lookup) {
		return "http://" + SERVER_EXTRAS + "/symbology/?symbol=" + lookup + "&provider=CQG&log=true";
	}
	
	/* #QUODDHACKS */
	
	public static void setLookupParams(final String params){
		if(params==null || !params.startsWith("&")){
			lookupParams="";
		}else{
			lookupParams = params;
		}
	}

	static List<String> buildSymbolQueries(final List<String> symbols) throws Exception {

		final List<String> queries = new ArrayList<String>();

		while(!symbols.isEmpty()) {

			final StringBuilder sb = new StringBuilder();
			int len = 0;
			int symCount = 0;

			while(len < MAX_URL_LEN && symCount < 400 && !symbols.isEmpty()) {

				final String s = symbols.remove(0);

				// log.debug("Pulled {} from remote queue", s);

				symCount++;
				len += s.length() + 1;
				sb.append(s).append(",");

			}

			/* Remove trailing comma */
			sb.deleteCharAt(sb.length() - 1);

			queries.add(sb.toString());

			// log.debug("Sending {} to remote lookup", sb.toString());

		}

		return queries;

	}
	
	static List<String> buildIDQueries(final List<InstrumentID> ids) throws Exception {

		final List<String> queries = new ArrayList<String>();

		while(!ids.isEmpty()) {

			final StringBuilder sb = new StringBuilder();
			int len = 0;
			int symCount = 0;

			while(len < MAX_URL_LEN && symCount < 400 && !ids.isEmpty()) {

				final String s = ids.remove(0).id();

				// log.debug("Pulled {} from remote queue", s);

				symCount++;
				len += s.length() + 1;
				sb.append(s).append(",");

			}

			/* Remove trailing comma */
			sb.deleteCharAt(sb.length() - 1);

			queries.add(sb.toString());

			// log.debug("Sending {} to remote lookup", sb.toString());

		}

		return queries;

	}

}
