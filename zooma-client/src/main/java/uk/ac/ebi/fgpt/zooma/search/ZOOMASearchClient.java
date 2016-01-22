package uk.ac.ebi.fgpt.zooma.search;

import static java.util.Collections.sort;
import static org.apache.commons.lang3.StringUtils.abbreviate;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import uk.ac.ebi.fgpt.zooma.exception.SearchException;
import uk.ac.ebi.fgpt.zooma.model.Annotation;
import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.AnnotationProvenance;
import uk.ac.ebi.fgpt.zooma.model.AnnotationSource;
import uk.ac.ebi.fgpt.zooma.model.BiologicalEntity;
import uk.ac.ebi.fgpt.zooma.model.Property;
import uk.ac.ebi.fgpt.zooma.model.SimpleAnnotation;
import uk.ac.ebi.fgpt.zooma.model.SimpleAnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.SimpleAnnotationProvenance;
import uk.ac.ebi.fgpt.zooma.model.SimpleAnnotationSource;
import uk.ac.ebi.fgpt.zooma.model.SimpleBiologicalEntity;
import uk.ac.ebi.fgpt.zooma.model.SimpleStudy;
import uk.ac.ebi.fgpt.zooma.model.SimpleTypedProperty;
import uk.ac.ebi.fgpt.zooma.model.Study;
import uk.ac.ebi.fgpt.zooma.model.TypedProperty;
import uk.ac.ebi.fgpt.zooma.util.URIUtils;
import uk.ac.ebi.utils.io.IOUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;

/**
 * A simple search client stub that takes a list of properties and uses them to search a ZOOMA service.
 * @see AbstractZOOMASearch.
 *
 * @author Tony Burdett
 * @author Adam Faulconbridge
 * @date 03/09/12
 */
public class ZOOMASearchClient extends AbstractZOOMASearch
{
	private final String zoomaBase;

	private final String zoomaAnnotationsBase;

	private final String zoomaServicesBase;

	private final String zoomaAnnotateServiceBase;

	private final String zoomaPropertyValueArgument;
	private final String zoomaPropertyTypeArgument;
	private final String zoomaFilterArgument;
	private final String zoomaArgumentSeparator;

	private final String zoomaRequiredParam;
	private final String zoomaPreferredParam;
	private final String zoomaFilterParamStart;
	private final String zoomaFilterParamEnd;
	private final String zoomaFilterParamSeparator;

	private Map<String, String> prefixMappings;

	public ZOOMASearchClient ()
	{
		this ( (String) null );
	}

	public ZOOMASearchClient ( URL zoomaLocation )
	{
		this ( zoomaLocation == null ? null : zoomaLocation.toString () );
	}

	public ZOOMASearchClient ( String zoomaLocation )
	{
		if ( zoomaLocation == null )
			zoomaLocation = System.getProperty (
					"uk.ac.ebi.fg.biosd.biosd2rdf.zooma.apiurl",
					"http://www.ebi.ac.uk/fgpt/zooma" );

		this.zoomaBase = zoomaLocation + "/v2/api/";

		this.zoomaAnnotationsBase = zoomaBase + "annotations/";

		this.zoomaServicesBase = zoomaBase + "services/";

		this.zoomaAnnotateServiceBase = zoomaServicesBase + "annotate?";

		this.zoomaPropertyValueArgument = "propertyValue=";
		this.zoomaPropertyTypeArgument = "propertyType=";
		this.zoomaFilterArgument = "filter=";
		this.zoomaArgumentSeparator = "&";

		this.zoomaRequiredParam = "required:";
		this.zoomaPreferredParam = "preferred:";
		this.zoomaFilterParamStart = "[";
		this.zoomaFilterParamEnd = "]";
		this.zoomaFilterParamSeparator = ",";

		loadPrefixMappings ();
	}

	public Map<String, String> getPrefixMappings () throws IOException
	{
		URL prefixMappingsURL = new URL ( zoomaServicesBase + "prefixMappings" );
		ObjectMapper mapper = new ObjectMapper ();
		Map<String, String> results = mapper.readValue ( prefixMappingsURL,
				new TypeReference<Map<String, String>> () {
				} );
		log.trace ( results.toString () );
		return results;
	}

	/**
	 * TODO: (MB) for the moment returns only the URIs (semanticTags) and the confidence. That's because I found it in a 
	 * totally screwed state, and I don't have time to fix it, so I'm fixing only what I need. 
	 */
	@Override
	public List<AnnotationPrediction> annotate ( Property property )
	{
		if ( ! this.checkStringLimits ( property, ! ( property instanceof TypedProperty ) ) )
			return new ArrayList<> ();
		
		String query = property.getPropertyValue ();

		List<String> requiredSources = this.getRequiredSources ();
		if ( requiredSources == null ) requiredSources = Collections.emptyList ();
		
		List<String> preferredSources = this.getPreferredSources ();
		if ( preferredSources == null ) preferredSources = Collections.emptyList ();
		
		AnnotationPrediction.Confidence minConfidence = this.getMinConfidence ();
		double minScore = minConfidence == null ? -1 : minConfidence.getScore ();
		
		// get annotation predictions
		try
		{
			// construct the URL from supplied args
			String searchUrl = zoomaAnnotateServiceBase + zoomaPropertyValueArgument
					+ URLEncoder.encode ( property.getPropertyValue (), "UTF-8" );
			searchUrl = property instanceof TypedProperty ? searchUrl
					+ zoomaArgumentSeparator
					+ zoomaPropertyTypeArgument
					+ URLEncoder.encode (
							( (TypedProperty) property ).getPropertyType (), "UTF-8" )
					: searchUrl;
			if ( !requiredSources.isEmpty () || !preferredSources.isEmpty () )
			{
				StringBuilder filters = new StringBuilder ();
				filters.append ( zoomaArgumentSeparator ).append ( zoomaFilterArgument );
				if ( !requiredSources.isEmpty () )
				{
					filters.append ( zoomaRequiredParam ).append ( zoomaFilterParamStart );
					Iterator<String> requiredIt = requiredSources.iterator ();
					while ( requiredIt.hasNext () )
					{
						filters.append ( requiredIt.next () );
						if ( requiredIt.hasNext () )
						{
							filters.append ( zoomaFilterParamSeparator );
						}
					}
					filters.append ( zoomaFilterParamEnd );
				}
				if ( !preferredSources.isEmpty () )
				{
					filters.append ( zoomaPreferredParam )
							.append ( zoomaFilterParamStart );
					Iterator<String> preferredIt = preferredSources.iterator ();
					while ( preferredIt.hasNext () )
					{
						filters.append ( preferredIt.next () );
						if ( preferredIt.hasNext () )
						{
							filters.append ( zoomaFilterParamSeparator );
						}
					}
					filters.append ( zoomaFilterParamEnd );
				}
				searchUrl = searchUrl.concat ( filters.toString () );
			}

			// OK, go to the web service and map the JSON result
			List<AnnotationPrediction> result = annotate ( searchUrl );

			// Filter on minConfidence
			if ( minConfidence != null )
				for ( Iterator<AnnotationPrediction> itr = result.iterator (); itr.hasNext (); )
				{
					AnnotationPrediction ann = itr.next ();
					if ( ann.getConfidence ().getScore () < minScore ) itr.remove (); 
			}
					
			// Sort
			if ( this.isOrderedResults () )
				sort ( result, new Comparator<AnnotationPrediction> () 
				{
					@Override
					public int compare ( AnnotationPrediction ann1, AnnotationPrediction ann2 ) {
						return (int) ( ann2.getConfidence ().getScore () - ann1.getConfidence ().getScore () );
					}
			});

			return result;
		}
		catch ( IOException e )
		{
			log.error ( "Failed to query ZOOMA for property '" + query + "' ("
					+ e.getMessage () + ")" );
			throw new RuntimeException ( "Failed to query ZOOMA for property '"
					+ query + "' " + "(" + e.getMessage () + ")", e );
		}
	}

	public Annotation getAnnotation ( URI annotationURI ) throws SearchException
	{
		try
		{
			String shortname = lookupShortname ( annotationURI );
			URL fetchURL = new URL ( zoomaAnnotationsBase + shortname );

			// populate required fields from result of query
			Collection<BiologicalEntity> biologicalEntities = new ArrayList<> ();
			Property annotatedProperty = null;
			AnnotationProvenance annotationProvenance = null;
			List<URI> semanticTags = new ArrayList<> ();

			ObjectMapper mapper = new ObjectMapper ();
			JsonNode annotationNode = null;
			int tries = 0;
			boolean success = false;
			IOException lastException = null;
			while ( !success & tries < 3 )
			{
				try
				{
					annotationNode = mapper.readValue ( fetchURL, JsonNode.class );
					success = true;
				}
				catch ( IOException e )
				{
					// could be due to an intermittent HTTP 500 exception, allow a couple of retries
					tries++;
					log.error ( e.getMessage () + ": retrying.  Retries remaining = "
							+ ( 3 - tries ) );
					lastException = e;
				}
			}
			if ( !success )
			{
				throw lastException;
			}

			log.trace ( "Got the following result from <" + fetchURL + ">...\n"
					+ annotationNode.toString () );

			JsonNode propertyNode = annotationNode.get ( "annotatedProperty" );
			if ( propertyNode != null )
			{
				annotatedProperty = new SimpleTypedProperty ( propertyNode.get (
						"propertyType" ).asText (), propertyNode.get ( "propertyValue" )
						.asText () );
			}

			JsonNode biologicalEntitiesNode = annotationNode
					.get ( "annotatedBiologicalEntities" );
			if ( biologicalEntitiesNode != null )
			{
				for ( JsonNode biologicalEntityNode : biologicalEntitiesNode )
				{
					List<Study> studies = new ArrayList<> ();
					JsonNode studiesNode = biologicalEntityNode.get ( "studies" );
					for ( JsonNode studyNode : studiesNode )
					{
						Study study = new SimpleStudy ( URI.create ( studyNode.get ( "uri" )
								.asText () ), studyNode.get ( "accession" ).asText () );
						studies.add ( study );
					}

					BiologicalEntity be = new SimpleBiologicalEntity (
							URI.create ( biologicalEntityNode.get ( "uri" ).asText () ),
							biologicalEntityNode.get ( "name" ).asText (),
							studies.toArray ( new Study[ studies.size () ] ) );
					biologicalEntities.add ( be );
				}
			}

			JsonNode provenanceNode = annotationNode.get ( "provenance" );
			if ( provenanceNode != null )
			{
				JsonNode sourceNode = provenanceNode.get ( "source" );
				if ( sourceNode != null )
				{
					AnnotationSource.Type type = AnnotationSource.Type
							.valueOf ( sourceNode.get ( "type" ).asText () );
					AnnotationSource annotationSource = new SimpleAnnotationSource (
							URI.create ( sourceNode.get ( "uri" ).asText () ), sourceNode
									.get ( "name" ).asText (), type );
					annotationProvenance = new SimpleAnnotationProvenance (
							annotationSource,
							AnnotationProvenance.Evidence.valueOf ( provenanceNode.get (
									"evidence" ).asText () ), provenanceNode.get ( "generator" )
									.asText (), new Date ( provenanceNode.get ( "generatedDate" )
									.asLong () ) );
				}
			}

			JsonNode stsNode = annotationNode.get ( "semanticTags" );
			for ( JsonNode stNode : stsNode )
			{
				URI de = URI.create ( stNode.asText () );
				semanticTags.add ( de );
			}

			// create and return the annotation
			return new SimpleAnnotation ( annotationURI, biologicalEntities,
					annotatedProperty, annotationProvenance,
					semanticTags.toArray ( new URI[ semanticTags.size () ] ) );
		}
		catch ( IOException e )
		{
			log.error ( "Failed to query ZOOMA for annotation '"
					+ annotationURI.toString () + "' " + "(" + e.getMessage () + ")" );
			throw new RuntimeException ( "Failed to query ZOOMA for annotation '"
					+ annotationURI.toString () + "' " + "(" + e.getMessage () + ")", e );
		}
	}

	public String getLabel ( URI uri ) throws IOException, SearchException
	{
		if ( uri == null )
		{
			throw new IllegalArgumentException ( "Cannot lookup label for URI 'null'" );
		}

		String shortform = null;
		try
		{
			shortform = URIUtils.getShortform ( prefixMappings, uri );
		}
		catch ( IllegalArgumentException e )
		{
			throw new SearchException ( "Failed to lookup label for <"
					+ uri.toString () + ">", e );
		}
		if ( shortform != null )
		{
			log.trace ( "Formulating search for label of '" + shortform
					+ "' (derived from <" + uri + ">)" );
			URL labelsURL = new URL ( zoomaServicesBase + "labels/" + shortform );
			ObjectMapper mapper = new ObjectMapper ();
			Map<String, Set<String>> labelMap = mapper.readValue ( labelsURL,
					new TypeReference<Map<String, Set<String>>> () {
					} );
			return labelMap.get ( "label" ).iterator ().next ();
		} else
		{
			String msg = "URI <" + uri + "> resolved to 'null' shortform";
			log.error ( msg );
			throw new RuntimeException ( msg );
		}
	}

	public Collection<String> getSynonyms ( URI uri ) throws IOException
	{
		String shortform = URIUtils.getShortform ( prefixMappings, uri );
		log.trace ( "Formulating search for synonyms of '" + shortform
				+ "' (derived from <" + uri + ">)" );
		URL labelsURL = new URL ( zoomaServicesBase + "labels/" + shortform );
		ObjectMapper mapper = new ObjectMapper ();
		Map<String, Set<String>> labelMap = mapper.readValue ( labelsURL,
				new TypeReference<Map<String, Set<String>>> () {
				} );
		return labelMap.get ( "synonyms" );
	}

	private String lookupShortname ( URI uri )
	{
		// try to recover URI
		String shortname;
		try
		{
			shortname = URIUtils.getShortform ( prefixMappings, uri );
		}
		catch ( IllegalArgumentException e )
		{
			// if we get an illegal argument exception, refresh cache and retry
			log.debug ( e.getMessage ()
					+ ": reloading prefix mappings cache and retrying..." );
			loadPrefixMappings ();
			shortname = URIUtils.getShortform ( prefixMappings, uri );
		}
		return shortname;
	}

	private void loadPrefixMappings ()
	{
		Map<String, String> mappings;
		try
		{
			mappings = getPrefixMappings ();
		}
		catch ( IOException e )
		{
			log.error ( "Unable to retrieve prefix mappings, using defaults" );
			mappings = new HashMap<> ();
		}
		this.prefixMappings = Collections.unmodifiableMap ( mappings );
	}

	/**
	 * Invokes the REST /annotate call and maps the JSON result to {@link AnnotationPrediction} and dependant classes.
	 * Note that as explained in {@link #annotate(Property)}, I currently only consider the essentials. Automatic Jackson
	 * JSON/Java mapping doesn't work cause the ZOOMA target classes don't have empty constructors yet.   
	 * 
	 */
	private List<AnnotationPrediction> annotate ( String queryURL )
	{
		try
		{
			List<AnnotationPrediction> result = new ArrayList<AnnotationPrediction> ();

			log.trace ( "Sending query [" + queryURL + "]..." );

			ObjectMapper mapper = new ObjectMapper ();
			JsonNode js = mapper.readValue ( new URL ( queryURL ), JsonNode.class );
			if ( js == null ) return result;
			
			
			for ( JsonNode jsann: js )
			{
				// If it's to be replace by something else, let's hope the replacement is in the results too
				// TODO: check
				JsonNode jsReplaceBy = js.get ( "replacedBy" );
				if ( ! ( jsReplaceBy == null || jsReplaceBy.size () == 0 ) ) continue;
								
				String jsConfidence = StringUtils.trimToNull ( jsann.get ( "confidence" ).asText () );
				if ( jsConfidence == null ) continue;
				AnnotationPrediction.Confidence confidence = AnnotationPrediction.Confidence.valueOf ( jsConfidence );
				
				JsonNode jsSemTags = jsann.get ( "semanticTags" );
				if ( !( jsSemTags != null && jsSemTags.isArray () && jsSemTags.size () >  0 ) ) continue;
				URI semTags[] = FluentIterable.from ( jsSemTags ).transform ( new Function<JsonNode, URI>() 
				{
					@Override
					public URI apply ( JsonNode jsn ) {
						return IOUtils.uri ( jsn.asText () );
					}
				}).toArray ( URI.class );
				if ( semTags == null || semTags.length == 0 ) continue;
				
				result.add ( new SimpleAnnotationPrediction ( null, confidence, null, null, null, semTags ) );
			
			} // for jsann
			
			return result;
		}
		catch ( IOException ex )
		{
			throw new RuntimeException ( 
				"Error while ZOOMA API with '" + abbreviate ( queryURL, 20 ) + "': " + ex.getMessage (), ex 
			);
		}
	} // annotate()
}
