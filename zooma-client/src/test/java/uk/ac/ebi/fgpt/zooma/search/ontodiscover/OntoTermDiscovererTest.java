package uk.ac.ebi.fgpt.zooma.search.ontodiscover;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static uk.ac.ebi.fgpt.zooma.search.ZOOMASearchClientTest.ZOOMA_BASE_URL;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.search.ZOOMASearchClient;
import uk.ac.ebi.onto_discovery.api.CachedOntoTermDiscoverer;
import uk.ac.ebi.onto_discovery.api.OntoTermDiscoveryMemCache;
import uk.ac.ebi.onto_discovery.api.OntologyTermDiscoverer;
import uk.ac.ebi.onto_discovery.api.OntologyTermDiscoverer.DiscoveredTerm;
import uk.ac.ebi.utils.time.XStopWatch;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Tests the uk.ac.ebi.fgpt.zooma.search.ontodiscover package.
 *
 * <dl><dt>date</dt><dd>31 Jul 2014</dd></dl>
 * @author Marco Brandizi
 *
 */
public class OntoTermDiscovererTest
{
	private Logger log = LoggerFactory.getLogger ( this.getClass () );
	
	@Test
	public void testBasics ()
	{
		ZOOMASearchClient zclient = new ZOOMASearchClient ( ZOOMA_BASE_URL );
		zclient.setMinConfidence ( AnnotationPrediction.Confidence.fromScore ( 50d ) );
		zclient.setOrderedResults ( true );
		
		OntologyTermDiscoverer client = new ZoomaOntoTermDiscoverer ( zclient );
		List<DiscoveredTerm> terms = client.getOntologyTerms ( "homo sapiens", "specie" );

		log.info ( "Discovered terms for Homo Sapiens:\n" + terms );
		
		boolean hasNCBITaxon_9606 = false, hasDupes = false;
		Set<String> uris = new HashSet<> ();
		for ( DiscoveredTerm term: terms )
		{
			hasNCBITaxon_9606 = hasNCBITaxon_9606 || StringUtils.contains ( term.getIri (), "NCBITaxon_9606" );
			if ( uris.contains ( term.getIri () ) )
			{
				log.error ( "Ouch! the term '{}' is duplicated!", term.getIri () );
				hasDupes = true;
			}
		}
		
		assertTrue ( "Damn! I couldn't find NCBITax:9606!", hasNCBITaxon_9606 );
		assertFalse ( "Oh no! results has duplicates!", hasDupes );
	}
	
	
	@Test
	public void testCache()
	{
		XStopWatch timer = new XStopWatch ();
		
		CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.newBuilder ().maximumSize ( 1000 );
		Cache<String, List<DiscoveredTerm>> cache = cacheBuilder.build();
		ConcurrentMap<String, List<DiscoveredTerm>> baseCache = cache.asMap ();
		
		OntologyTermDiscoverer client = new CachedOntoTermDiscoverer ( 
			new ZoomaOntoTermDiscoverer ( 
				new ZOOMASearchClient ( ZOOMA_BASE_URL ) ),
				new OntoTermDiscoveryMemCache ( baseCache )
		);
		
		timer.start ();
		List<DiscoveredTerm> terms = client.getOntologyTerms ( "homo sapiens", "organism" );
		long time1 = timer.getTime ();
				
		assertEquals ( "entry not saved in the cache!", terms, baseCache.get ( "organism:homo sapiens" ) );
		
		timer.reset ();
		timer.start ();
		for ( int i = 0; i < 100; i++ )
		{
			terms = client.getOntologyTerms ( "homo sapiens", "organism" );
			log.trace ( "Call {}, time {}", i, timer.getTime () );
		}
		timer.stop ();
		
		double time2 = timer.getTime () / 100.0;
		
		log.info ( "Second-call versus first-call time: {}, {}", time2, time1 );
		assertTrue ( "WTH?! Second call time bigger than first!", time2 < time1 );
	}
	
}
