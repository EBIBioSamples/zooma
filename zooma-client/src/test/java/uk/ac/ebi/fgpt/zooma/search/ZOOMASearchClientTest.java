package uk.ac.ebi.fgpt.zooma.search;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.SimpleTypedProperty;
import uk.ac.ebi.fgpt.zooma.model.TypedProperty;

import com.google.code.tempusfugit.concurrency.ConcurrentRule;
import com.google.code.tempusfugit.concurrency.RepeatingRule;
import com.google.code.tempusfugit.concurrency.annotations.Concurrent;
import com.google.code.tempusfugit.concurrency.annotations.Repeating;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;

/**
 * 
 * Basic tests for {@link ZOOMASearchClient}.
 *
 * @author brandizi
 *         <dl>
 *         <dt>Date:</dt>
 *         <dd>20 Jan 2016</dd>
 *         </dl>
 *
 */
public class ZOOMASearchClientTest
{
	public static final String ZOOMA_BASE_URL = "http://www.ebi.ac.uk/fgpt/zooma";
	
	private static AbstractZOOMASearch zoomaClient;
	
	
	Logger log = LoggerFactory.getLogger ( this.getClass () );
	
	@Rule
	public RepeatingRule repatRule = new RepeatingRule ();
	
	@Rule
	public ConcurrentRule concurrentRule = new ConcurrentRule ();

	static
	{
		System.setProperty ( StatsZOOMASearchFilter.STATS_SAMPLING_TIME_PROP_NAME, "" + ( 20 * 1000 ) );
		zoomaClient = new ZOOMASearchClient ( ZOOMA_BASE_URL );
		zoomaClient = new StatsZOOMASearchFilter ( zoomaClient );
		zoomaClient.setMinConfidence ( AnnotationPrediction.Confidence.GOOD );
		zoomaClient.setOrderedResults ( true );
	}

	
	
	@Test
	public void testAnnotate ()
	{
		ZOOMASearchClient client = new ZOOMASearchClient ( ZOOMA_BASE_URL );
		List<AnnotationPrediction> anns = client.annotate ( 
			new SimpleTypedProperty ( "organism part", "head and thorax" ) 
		);
		
		assertNotNull ( "annotate() returns null!" );
		boolean uberonFound = false;
		for ( AnnotationPrediction ann : anns )
		{
			Collection<URI> uris = ann.getSemanticTags ();
			log.info ( "Found: {} {}", uris, ann.getConfidence () );
			
			uberonFound |= FluentIterable
				.from ( ann.getSemanticTags () )
				.anyMatch ( new Predicate<URI> () {
					@Override
					public boolean apply ( URI uri )
					{
						return "http://purl.obolibrary.org/obo/UBERON_0000033".equals ( uri.toASCIIString () );
					}
			});
		}
		
		assertTrue ( "Expected annotating term not found!", uberonFound );
	}

	@Test
	public void testAnnotateMinConfidence ()
	{
		AbstractZOOMASearch client = new ZOOMASearchClient ( ZOOMA_BASE_URL );
		client.setMinConfidence ( AnnotationPrediction.Confidence.HIGH );

		List<AnnotationPrediction> anns = client
			.annotate ( new SimpleTypedProperty ( "organism part", "head and thorax" ) );

		assertTrue ( "Didn't filter!", anns.isEmpty () );
	}

	@Test
	public void testAnnotateIsOrdered ()
	{
		ZOOMASearchClient client = new ZOOMASearchClient ( ZOOMA_BASE_URL );
		client.setOrderedResults ( true );

		List<AnnotationPrediction> anns = client.annotate ( new SimpleTypedProperty ( "Disease", "BRCA1" ) );

		AnnotationPrediction.Confidence prevConf = AnnotationPrediction.Confidence.HIGH;
		for ( AnnotationPrediction ann : anns ) 
		{
			log.info ( "Found: {} {}" + ann.getURI (), ann.getConfidence () );
			assertTrue ( "Wrong order!", ann.getConfidence ().getScore () <= prevConf.getScore () );
			prevConf = ann.getConfidence ();
		}
	}
	
	/**
	 * Results on 26/1/2016 (on a MacBook Pro, 2.6 GHz):
	 * 
	 * <table>
	 *   <tr><th>Threads</th><th>Runs x thread</th><th>Calls/min</th><th>% Fails</th></tr>
	 *   <tr><td>1</td>    <td>500</td>    <td>132</td>    <td>10</td></tr>
	 *   <tr><td>3</td>    <td>500</td>    <td>450</td>    <td>10</td></tr>
	 *   <tr><td>5</td>    <td>500</td>    <td>1249</td>   <td>10</td></tr>
	 *   <tr><td>10</td>    <td>500</td>    <td>1154</td>   <td>10</td></tr>
	 *   <tr><td>50</td>    <td>500</td>    <td>1258</td>   <td>10</td></tr>
	 *   <tr><td>100</td>    <td>500</td>    <td>Crash</td>   <td>100</td></tr>
	 * </table>
	 */
	@Test @Ignore( "Not a real test, too slow" )
	@Concurrent ( count = 50 )
	@Repeating ( repetition = 500 )
	public void testSpeed ()
	{
		try
		{
			int n1 = RandomUtils.nextInt ( 0, 30 ), n2 = RandomUtils.nextInt ( 0, 30 );
			String type = RandomStringUtils.randomAscii ( n1 ), value = RandomStringUtils.randomAscii ( n2 );
			
			TypedProperty p  = new SimpleTypedProperty ( type, value );
			@SuppressWarnings ( "unused" )
			List<AnnotationPrediction> anns = zoomaClient.annotate ( p );
			//log.info ( "input is: {}, result is: {}", p, anns );
		}
		catch ( Exception ex ) {
			 log.error ( "Call exception:" + ex.getMessage () );
		}		
	}
}
