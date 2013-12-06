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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.openfeed.proto.inst.InstrumentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import rx.Observable;

import com.barchart.feed.api.consumer.MetadataService.Result;
import com.barchart.feed.api.consumer.MetadataService.SearchContext;
import com.barchart.feed.api.model.meta.Instrument;
import com.barchart.feed.base.provider.Symbology;
import com.barchart.feed.ddf.instrument.provider.DDF_InstrumentProvider.RemoteRunner;
import com.barchart.feed.ddf.util.HelperXML;
import com.barchart.feed.inst.provider.InstrumentImpl;

public class DDF_RxInstrumentProvider {

	private static final Logger log = LoggerFactory.getLogger(
			DDF_RxInstrumentProvider.class);
	
	private static final int MAX_URL_LEN = 7500;
			
	static final ConcurrentMap<String, List<Instrument>> symbolMap =
			new ConcurrentHashMap<String, List<Instrument>>();
	
	/* ***** ***** ***** Begin Executor ***** ***** ***** */
	
	/**
	 * Default executor service with dameon threads
	 */
	// Consider ExecutorCompletionService
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
	
	/* ***** ***** ***** End Executor ***** ***** ***** */
	
	public static Observable<Result<Instrument>> fromString(final String... symbols) {
		return fromString(SearchContext.NULL, symbols);
	}
	
	public static Observable<Result<Instrument>> fromString(final SearchContext ctx, 
			final String... symbols) {
		
		return Observable.from(futureFromString(ctx, symbols));
	}
	
	public static Future<Result<Instrument>> futureFromString(final SearchContext ctx, 
			final String... symbols) {
		
		return executor.submit(callableFromString(ctx, symbols));
		
	}
	
	public static Callable<Result<Instrument>> callableFromString(final SearchContext ctx, 
			final String... symbols) {
		
		return new Callable<Result<Instrument>>() {

			@Override
			public Result<Instrument> call() throws Exception {
				
				final Map<String, List<Instrument>> res = 
						new HashMap<String, List<Instrument>>();
				
				final List<String> toBatch = new ArrayList<String>();
				
				/* Filter out cached symbols */
				for(String symbol : symbols) {
					
					symbol = Symbology.formatSymbol(symbol);
					
					if(symbolMap.containsKey(symbol)) {
						res.put(symbol, symbolMap.get(symbol));
					} else {
						toBatch.add(symbol);
					}
					
				}
				
				final List<String> queries = buildQueries(toBatch);
				
				for(final String query : queries) {
					
					final Map<String, List<Instrument>> lookup = remoteLookup(query);
					
					/* Store instruments returned from lookup */
					for(final Entry<String, List<Instrument>> e : lookup.entrySet()) {
						symbolMap.put(e.getKey(), e.getValue());
					}
					
					res.putAll(lookup);
				}
				
				return result(res);
			}
			
		};
		
	}
	
	private static Map<String, List<Instrument>> remoteLookup(final String query) {
		
		try {
		
			final Map<String, List<Instrument>> result = 
					new HashMap<String, List<Instrument>>();
			
			log.debug("remote batch on {}", urlInstrumentLookup(query));
			
			final URL url = new URL(urlInstrumentLookup(query));
			
			final HttpURLConnection connection = (HttpURLConnection) url
					.openConnection();
			
			connection.setRequestProperty("Accept-Encoding", "gzip");
			
			connection.connect();
			
			InputStream input = connection.getInputStream();
	
			if (connection.getContentEncoding().equals("gzip")) {
				input = new GZIPInputStream(input);
			}
			
			final BufferedInputStream stream =
					new BufferedInputStream(input);
	
			final SAXParserFactory factory =
					SAXParserFactory.newInstance();
			final SAXParser parser = factory.newSAXParser();
			
			final DefaultHandler handler = new DefaultHandler() {
				
				@Override
				public void startElement(final String uri,
						final String localName, final String qName,
						final Attributes ats) throws SAXException {
					
					if (qName != null && qName.equals("instrument")) {

						try {
							
							final String lookup = xmlStringDecode(ats, LOOKUP, XML_STOP);
							final InstrumentDefinition def = InstrumentXML.decodeSAX(ats);
							Instrument inst;
							
							if (def != InstrumentDefinition.getDefaultInstance()) {
								inst = new InstrumentImpl(def);
							} else {
								inst = Instrument.NULL;
							}
							
							final List<Instrument> insts = new ArrayList<Instrument>();
							insts.add(inst);
							result.put(lookup, insts);
							
						} catch (final SymbolNotFoundException se) {
							throw new RuntimeException(se); // would be nice to add to map
						} catch (final Exception e) {
							throw new RuntimeException(e);
						}
						
					}
					
				}
				
			};
			
			parser.parse(stream, handler);
			
			return result;
		
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
		
	}
	
	/* ***** ***** ***** CQG ***** ***** ***** */
	
	private static final ConcurrentMap<String, String> cqgSymMap =
			new ConcurrentHashMap<String, String>();
	
	public static Observable<String> fromCQGString(final String symbol) {
		try {
			return Observable.from(executor.submit(
					callableFromCQGString(SearchContext.NULL, symbol)));
		} catch (Exception e) {
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
				
				final Element root = HelperXML.xmlDocumentDecode(query);

		        final Element tag = xmlFirstChild(root, "symbol", XML_STOP);
		        
		        final String result = tag.getTextContent();
		        
		        if(result != null) {
		        	cqgSymMap.put(symbol, result);
		        }
		        
		        return result;
				
			}
			
		};
		
	}
	
	private static Result<Instrument> result(final Map<String, List<Instrument>> res) {
		
		return new Result<Instrument>() {

			@Override
			public SearchContext context() {
				return SearchContext.NULL;
			}

			@Override
			public Map<String, List<Instrument>> results() {
				return res;
			}

			@Override
			public boolean isNull() {
				return false;
			}}
		;
		
	}
	
	/* ***** ***** ***** Begin Remote Lookup ***** ***** ***** */
	
	private static final String SERVER_EXTRAS = "extras.ddfplus.com";

	private static final String urlInstrumentLookup(final CharSequence lookup) {
		return "http://" + SERVER_EXTRAS + "/instruments/?lookup=" + lookup;
	}
	
	private static final String cqgInstLoopURL(final CharSequence lookup) {
		return "http://" + SERVER_EXTRAS + "/symbology/?symbol=" + lookup +
                 "&provider=CQG";
	}
	
	static List<String> buildQueries(final List<String> symbols) throws Exception {
		
		final List<String> queries = new ArrayList<String>();
		
		while(!symbols.isEmpty()) {
		
			final StringBuilder sb = new StringBuilder();
			int len = 0;
			int symCount = 0;
			
			while(len < MAX_URL_LEN && symCount < 400 && !symbols.isEmpty()) {
			
				final String s = symbols.remove(0);
			
				log.debug("Pulled {} from remote queue", s);
			
				symCount++;
				len += s.length() + 1;
				sb.append(s).append(",");
			
			}
		
			/* Remove trailing comma */
			sb.deleteCharAt(sb.length() - 1);
			
			queries.add(sb.toString());
			
			log.debug("Sending {} to remote lookup", sb.toString());
		
		}
		
		return queries;
		
	}
	
}