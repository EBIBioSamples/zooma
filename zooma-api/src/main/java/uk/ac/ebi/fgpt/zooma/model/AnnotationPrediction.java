package uk.ac.ebi.fgpt.zooma.model;

/**
 * A ZOOMA prediction for a new annotation, based on a search and the best available matched existing annotation ZOOMA
 * could find.  Annotation predictions are extensions of an {@link Annotation} but with an indication of confidence in
 * how likely ZOOMA considers this prediction to be the correct one.
 *
 * @author Tony Burdett
 * @date 14/08/15
 */
public interface AnnotationPrediction extends Annotation {
    /**
     * Returns an annotation in ZOOMA that was used when making this annotation prediction.  This can be considered as
     * the "best canonical example" of an annotation being predicted.
     *
     * @return an annotation that exists in ZOOMA that was used in making this prediction
     */
    Annotation getDerivedFrom();

    /**
     * A measure of the confidence ZOOMA has in the quality of this prediction.  May be high, good, medium or low.  You
     * should only really consider HIGH confidence matches to be worthy of use in automated processes - other levels of
     * confidence should be reviewed before accepting.  Low confidence matches can be used to identify areas of need.
     *
     * @return the confidence ZOOMA has in this annotation prediction
     */
    Confidence getConfidence();

    enum Confidence 
    {
    		LOW ( 12.5 ), MEDIUM ( 37.5 ), GOOD ( 62.5 ), HIGH ( 87.5 );
        
        private final double score;
        
        private Confidence ( double score ) {
        	this.score = score;
        }

        /**
         * In order to help some degree of backward compatibility, a numerical 0-100 percent value is associated to 
         * each confidence level.
         * 
         * Score values are, in order, 12.5, 37.5, 62.5, 87.5. These are the centres of the quartile intervals 
         * (0, 25, 50, 75, 100) and {@link #fromScore(double)} returns the first confidence value that is &lt= to the
         * parameter of that method, that is the parameter is associated to the closest quartile.
         *
         */
				public double getScore ()
				{
					return score;
				}
				
				/**
				 * @return a confidence level such that the parameter is closest to the corresponding quartile.
				 * @see #getScore().
				 */
				public static Confidence fromScore ( double score ) 
				{
					for ( Confidence conf: Confidence.values () )
						if ( score <= conf.getScore () ) return conf;
					return HIGH;
				}
    }
}
