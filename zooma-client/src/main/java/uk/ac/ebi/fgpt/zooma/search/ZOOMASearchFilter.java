package uk.ac.ebi.fgpt.zooma.search;

import java.util.List;

import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction.Confidence;
import uk.ac.ebi.fgpt.zooma.model.Property;

/**
 * A filter for {@link AbstractZOOMASearch}, which is intended to implement decorators, performing some additional
 * operations before/after the upstream call. For example, @see {@link StatsZOOMASearchFilter}.
 * 
 * TODO: support new methods, as explained in {@link AbstractZOOMASearch}.
 *
 *
 * @author brandizi
 * <dl><dt>Date:</dt><dd>17 Dec 2014</dd>
 *
 */
public class ZOOMASearchFilter extends AbstractZOOMASearch
{
	protected AbstractZOOMASearch base;
	
	public ZOOMASearchFilter ( AbstractZOOMASearch base )
	{
		super ();
		this.base = base;
	}

	@Override
	public List<AnnotationPrediction> annotate ( Property property )
	{
		return base.annotate ( property );
	}

	
	@Override
	public int getMaxPropertyValueLength ()
	{
		return base.getMaxPropertyValueLength ();
	}

	@Override
	public void setMaxPropertyValueLength ( int maxPropertyValueLength )
	{
		base.setMaxPropertyValueLength ( maxPropertyValueLength );
	}

	@Override
	public int getMaxPropertyTypeLength ()
	{
		return base.getMaxPropertyTypeLength ();
	}

	@Override
	public void setMaxPropertyTypeLength ( int maxPropertyTypeLength )
	{
		base.setMaxPropertyTypeLength ( maxPropertyTypeLength );
	}

	public List<String> getRequiredSources ()
	{
		return base.getRequiredSources ();
	}

	public void setRequiredSources ( List<String> requiredSources )
	{
		base.setRequiredSources ( requiredSources );
	}

	public List<String> getPreferredSources ()
	{
		return base.getPreferredSources ();
	}

	public void setPreferredSources ( List<String> preferredSources )
	{
		base.setPreferredSources ( preferredSources );
	}

	public Confidence getMinConfidence ()
	{
		return base.getMinConfidence ();
	}

	public void setMinConfidence ( Confidence minConfidence )
	{
		base.setMinConfidence ( minConfidence );
	}

	public boolean isOrderedResults ()
	{
		return base.isOrderedResults ();
	}

	public void setOrderedResults ( boolean isOrderedResults )
	{
		base.setOrderedResults ( isOrderedResults );
	}
	
}
