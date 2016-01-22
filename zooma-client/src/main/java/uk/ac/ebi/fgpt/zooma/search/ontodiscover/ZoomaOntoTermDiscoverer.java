package uk.ac.ebi.fgpt.zooma.search.ontodiscover;

import static uk.ac.ebi.onto_discovery.api.CachedOntoTermDiscoverer.NULL_RESULT;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.queryparser.flexible.standard.QueryParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.Property;
import uk.ac.ebi.fgpt.zooma.model.SimpleTypedProperty;
import uk.ac.ebi.fgpt.zooma.model.SimpleUntypedProperty;
import uk.ac.ebi.fgpt.zooma.search.AbstractZOOMASearch;
import uk.ac.ebi.fgpt.zooma.search.StatsZOOMASearchFilter;
import uk.ac.ebi.fgpt.zooma.search.ZOOMASearchClient;
import uk.ac.ebi.onto_discovery.api.OntologyDiscoveryException;
import uk.ac.ebi.onto_discovery.api.OntologyTermDiscoverer;

/**
 * <a href = 'https://github.com/EBIBioSamples/onto-discovery-api'>Ontology Discoverer</a> based on 
 * <a href = 'http://www.ebi.ac.uk/fgpt/zooma/docs/'>ZOOMA2</a>.
 * 
 *
 * <dl><dt>date</dt><dd>23 Oct 2013</dd></dl>
 * @author Marco Brandizi
 *
 */
public class ZoomaOntoTermDiscoverer extends OntologyTermDiscoverer
{
	private AbstractZOOMASearch zoomaSearcher;
	
	private Logger log = LoggerFactory.getLogger ( this.getClass () );
	
	/**
	 * You can pass me your own ZOOMA client.
	 * Note that I will always enable {@link AbstractZOOMASearch#setOrderedResults(boolean) ordered results}, since 
	 * I need that to work correctly.
	 */
	public ZoomaOntoTermDiscoverer ( AbstractZOOMASearch zoomaSearcher )
	{
		zoomaSearcher.setOrderedResults ( true );
		this.zoomaSearcher = zoomaSearcher;		
	}

	/**
	 * Defaults to {@link StatsZOOMASearchFilter}, with a threshold of 80/GOOD and ordered results. 
	 */
	public ZoomaOntoTermDiscoverer ()
	{
		this ( new StatsZOOMASearchFilter ( new ZOOMASearchClient () ) );
		this.zoomaSearcher.setMinConfidence ( AnnotationPrediction.Confidence.fromScore ( 80d ) );
	}

	
	/**
	 * Uses the top-ranked result from {@link ZOOMASearchClient}.searchZOOMA(), it sends to it a pair of value and type
	 * label, depending on the fact that the type is null or not.
	 * 
	 * The value and type parameters are pre-processed with {@link QueryParserUtil#escape(String)}, so that
	 * calls to the underline text annotor requests are less error-prone (usually they're sent to Lucene).
	 *
	 */
	@Override
	public List<DiscoveredTerm> getOntologyTerms ( String valueLabel, String typeLabel ) throws OntologyDiscoveryException
	{
		try
		{
			if ( (valueLabel = StringUtils.trimToNull ( valueLabel )) == null ) return NULL_RESULT;
			typeLabel = StringUtils.trimToNull ( typeLabel );
			
			valueLabel = QueryParserUtil.escape ( valueLabel );
			if ( typeLabel != null ) typeLabel = QueryParserUtil.escape ( typeLabel );
			
			Property zprop = typeLabel == null 
				? new SimpleUntypedProperty ( valueLabel ) 
				: new SimpleTypedProperty ( typeLabel, valueLabel ); 
			
			List<AnnotationPrediction> zresult = zoomaSearcher.annotate ( zprop );
							
			if ( zresult == null || zresult.size () == 0 ) return NULL_RESULT;
			List<DiscoveredTerm> result = new ArrayList<> ();
			
			// ZOOMA returns summaries, where the same URI can appear multiple times. Here we're interested in a result without
			// repetitions, so we take the highest scored result only
			Set<String> alreadySeenUris = new HashSet<String> ();
			
			for ( AnnotationPrediction zann: zresult )
			{
				double score = zann.getConfidence ().getScore ();
				
				Collection<URI> semTags = zann.getSemanticTags (); 
				if ( semTags == null ) continue;
				
				for ( URI uri: semTags )
				{
					String uriStr = uri.toASCIIString ();
					if ( alreadySeenUris.contains ( uriStr ) ) continue;
					
					result.add ( new DiscoveredTerm ( uriStr, score, null, "ZOOMA" ) );
					alreadySeenUris.add ( uriStr );
				}
			}

			return result;
		} 
		catch ( Exception ex )
		{
			log.error ( String.format ( 
				"Error while consulting ZOOMA for '%s' / '%s': %s. Returning null", 
				valueLabel, typeLabel, ex.getMessage ()  
			));
			if ( log.isDebugEnabled () ) log.debug (  "Underline exception: ", ex );
			return null;
		}
	}

}
