package uk.ac.ebi.fgpt.zooma.search;

import static org.apache.commons.lang3.StringUtils.length;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction.Confidence;
import uk.ac.ebi.fgpt.zooma.model.Property;
import uk.ac.ebi.fgpt.zooma.model.TypedProperty;

/**
 * A common interface to represent the ZOMMA searching functionality.
 *
 * TODO: it's rather messed-up. I've only extracted {@link #annotate(Property, List, List)} from the original 
 * {@link ZOOMASearchClient} class. This is because I've started with writing this extension for what I need and I
 * still haven't found time to complete it.
 *
 * @author brandizi
 * <dl><dt>Date:</dt><dd>17 Dec 2014</dd>
 *
 */
public abstract class AbstractZOOMASearch
{
	private int maxPropertyValueLength = 150;
	private int maxPropertyTypeLength = 150;
	private List<String> requiredSources, preferredSources = null;
	private Confidence minConfidence = null;
	private boolean isOrderedResults = false;
	
	protected Logger log = LoggerFactory.getLogger ( this.getClass () );

	
	/**
	 * Mirrors the ZOOMA /annotate REST call. Looks for ontology terms corresponding to the input value/type text.
	 * The results are influenced by the options set with the getters in this class. 
	 * 
	 * @param property what you're looking for
	 */
	public abstract List<AnnotationPrediction> annotate ( Property property ); 
	
	
	
	/**
	 * @return true if the parameters are within {@link #getMaxPropertyValueLength()} and 
	 * {@link #getMaxPropertyTypeLength()}. This should be used to implement 
	 * {@link #searchZOOMA(Property, float, boolean, boolean)}.
	 */
	protected boolean checkStringLimits ( Property property, boolean excludeType ) 
  {
    if ( length ( property.getPropertyValue () )  > this.getMaxPropertyValueLength () ) return false;
    if ( excludeType || ! ( property instanceof TypedProperty ) ) return true;
    return length ( ( (TypedProperty) property ).getPropertyType() ) <= this.getMaxPropertyTypeLength ();
	}

	/**
	 * The client will ignore property values longer than this and it won't contact the web service if it meets them
	 * This defaults to 150, which we computed by looking at some statistics on the stuff stored in ZOOMA, i.e., 
	 * average property value length is 29 and 99% of them are shorter than 100.
	 */
	public int getMaxPropertyValueLength ()
	{
		return maxPropertyValueLength;
	}

	public void setMaxPropertyValueLength ( int maxPropertyValueLength )
	{
		this.maxPropertyValueLength = maxPropertyValueLength;
	}

	/**
	 * The client will ignore property types longer than this and it won't contact the web service if it meets them
	 * This defaults to 150, which we computed by looking at some statistics on the stuff stored in ZOOMA, i.e., 
	 * average property type length is 21 and it's never longer than 121.
	 */
	public int getMaxPropertyTypeLength ()
	{
		return maxPropertyTypeLength;
	}

	public void setMaxPropertyTypeLength ( int maxPropertyTypeLength )
	{
		this.maxPropertyTypeLength = maxPropertyTypeLength;
	}

	/**
	 * the list of sources which are required in making an annotation prediction.
	 */
	public List<String> getRequiredSources ()
	{
		return requiredSources;
	}

	public void setRequiredSources ( List<String> requiredSources )
	{
		this.requiredSources = requiredSources;
	}

	/**
	 * the list of sources, in order of preference, to predict an annotation from
	 */
	public List<String> getPreferredSources ()
	{
		return preferredSources;
	}

	public void setPreferredSources ( List<String> preferredSources )
	{
		this.preferredSources = preferredSources;
	}

	/**
	 * {@link #annotate(Property)} results having a {@link Confidence#getScore() score} below this threshold will be 
	 * filtered away.
	 */
	public Confidence getMinConfidence ()
	{
		return minConfidence;
	}

	public void setMinConfidence ( Confidence minConfidence )
	{
		this.minConfidence = minConfidence;
	}

	/**
	 * If true, {@link #annotate(Property)} will sort its results in descending order of Confidence, using the the 
	 * {@link Confidence#getScore() confidence scores}. That might slightly slow things down. 
	 * 
	 */
	public boolean isOrderedResults ()
	{
		return isOrderedResults;
	}

	public void setOrderedResults ( boolean isOrderedResults )
	{
		this.isOrderedResults = isOrderedResults;
	}

}