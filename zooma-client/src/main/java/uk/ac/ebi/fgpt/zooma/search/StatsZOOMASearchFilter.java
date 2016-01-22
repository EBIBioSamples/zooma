package uk.ac.ebi.fgpt.zooma.search;

import java.util.List;

import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.Property;
import uk.ac.ebi.utils.runcontrol.ChainExecutor;
import uk.ac.ebi.utils.runcontrol.DynamicRateExecutor;
import uk.ac.ebi.utils.runcontrol.StatsExecutor;

/**
 * A wrapper of {@link ZOOMASearchFilter}, which dynamically logs usage statistics (no of calls, throughput, etc).
 * It also has a capability to throttle the client throughput, so that the server is not crashed by too many
 * requests.
 *
 * <dl><dt>date</dt><dd>16 Nov 2014</dd></dl>
 * @author Marco Brandizi
 *
 */
public class StatsZOOMASearchFilter extends ZOOMASearchFilter
{
	/**
	 * Send in a property with this to change the period that we log statistics on issued calls (ms).
	 */
	public static final String STATS_SAMPLING_TIME_PROP_NAME = "uk.ac.ebi.fgpt.zooma.stats_sampling_time";

	/**
	 * Change this property to disable throttling. This is an internal policy that limit the API call hit rate, based on
	 * performance statistics.
	 *  
	 */
	public static final String THROTTLE_MODE_PROP_NAME = "uk.ac.ebi.fgpt.zooma.throttle";
	
	private boolean throttleMode = !"false".equals ( System.getProperty ( THROTTLE_MODE_PROP_NAME ) );

	
	/**
	 * We provide these wrappers, in case you want to tweak their parameters.
	 * 
	 * The throttler gradually throttle ZOOMA, depending on the quote of failed calls returned by 
	 * {@link StatsZOOMASearchFilter#STATS_WRAPPER}.
   *
	 */
	public final DynamicRateExecutor THROTTLE_WRAPPER = new DynamicRateExecutor () 
	{
		@Override
		protected double setNewRate ()
		{
			if ( !throttleMode ) return Double.MAX_VALUE;

			int totCalls = STATS_WRAPPER.getLastTotalCalls (); 
			if ( totCalls == 0 ) return Double.MAX_VALUE;
			
			double failedCalls = STATS_WRAPPER.getLastFailedCalls () / (double) totCalls;
			
			if ( failedCalls <= 0.1 ) 
			{
				if ( this.rateLimiter.getRate () != Double.MAX_VALUE ) {
					// was throttling, going back to normal
					log.info ( "ZOOMA back to good performance, throttling ends" );
				}
				return Double.MAX_VALUE;
			}
	
			// The thresholds are related to twice their values most of the time, eg, 
			// previous checkpoint it was 0, then it becomes 35, average is 17.5
			double delay = 
				failedCalls <= 0.30 ? 0.5d 
				: failedCalls <= 0.50 ? 5d 
				: failedCalls <= 0.70 ? 60d
				: 5 * 60d; 
	
			if ( this.rateLimiter.getRate () == Double.MAX_VALUE ) {
				// Wasn't throttling, starting now
				log.info ( "Throttling ZOOMA to avoid server crashing, calls are slowed down by {}ms per call", delay );
			}
		
			return 1d / delay;
		}
	};
	
	/**
	 * Reports statistics about throughtput and API call failure rate.
	 */
	public final StatsExecutor STATS_WRAPPER = new StatsExecutor ( 
		"ZOOMA", Long.parseLong ( System.getProperty ( STATS_SAMPLING_TIME_PROP_NAME, "" + 5 * 60 * 1000 ) ) 
	).setPopUpExceptions ( false );
	
	/**
	 * Used to chain the {@link #THROTTLE_WRAPPER throttle wrapper} and the {@link #STATS_WRAPPER statistics wrapper}.
	 */
	private ChainExecutor wrapExecutor = new ChainExecutor (
		THROTTLE_WRAPPER,
		  STATS_WRAPPER
	);  


	public StatsZOOMASearchFilter ( AbstractZOOMASearch base )
	{
		super ( base );
	}
	

	@Override
	public List<AnnotationPrediction> annotate ( final Property property )
	{
		@SuppressWarnings ( "unchecked" )
		final List<AnnotationPrediction>[] result = new List [ 1 ];
		wrapExecutor.execute ( new Runnable () {
			@Override	
			public void run () {
				result [ 0 ] = base.annotate ( property );
			}
		});
		return result [ 0 ];
	}

}
