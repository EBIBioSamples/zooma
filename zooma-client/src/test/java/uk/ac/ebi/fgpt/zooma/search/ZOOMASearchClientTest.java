package uk.ac.ebi.fgpt.zooma.search;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.SimpleTypedProperty;

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
	
	Logger log = LoggerFactory.getLogger ( this.getClass () );
			
	@Test
	public void testAnnotate ()
	{
		ZOOMASearchClient client = new ZOOMASearchClient ( ZOOMA_BASE_URL );
		List<AnnotationPrediction> anns = client
			.annotate ( new SimpleTypedProperty ( "organism part", "head and thorax" ) );
		
		assertNotNull ( "annotate() returns null!" );
		boolean uberonFound = false;
		for ( AnnotationPrediction ann : anns )
		{
			Collection<URI> uris = ann.getSemanticTags ();
			log.info ( "Found: {} {}" + uris, ann.getConfidence () );
			
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

		List<AnnotationPrediction> anns = client
			.annotate ( new SimpleTypedProperty ( "Disease", "BRCA1" ) );

		AnnotationPrediction.Confidence prevConf = AnnotationPrediction.Confidence.HIGH;
		for ( AnnotationPrediction ann : anns ) 
		{
			log.info ( "Found: {} {}" + ann.getURI (), ann.getConfidence () );
			assertTrue ( "Wrong order!", ann.getConfidence ().getScore () <= prevConf.getScore () );
			prevConf = ann.getConfidence ();
		}
	}
}
