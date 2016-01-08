package uk.ac.ebi.fgpt.zooma.service;

import uk.ac.ebi.fgpt.zooma.Initializable;
import uk.ac.ebi.fgpt.zooma.model.AnnotationSummary;

import java.net.URI;
import java.util.Collection;
import java.util.List;

/**
 * An abstract decorator of an {@link AnnotationSummarySearchService}.  You should subclass this decorator to create
 * different decorations that add functionality to annotation summary searches.
 *
 * @author Tony Burdett
 * @date 02/08/13
 * @see AnnotationSummarySearchService
 */
public abstract class AnnotationSummarySearchServiceDecorator extends Initializable
        implements AnnotationSummarySearchService {
    private final AnnotationSummarySearchService _annotatationSummarySearchService;

    public AnnotationSummarySearchServiceDecorator(AnnotationSummarySearchService annotationSummarySearchService) {
        this._annotatationSummarySearchService = annotationSummarySearchService;
    }

    @Override public Collection<AnnotationSummary> search(String propertyValuePattern, URI... sources) {
        return _annotatationSummarySearchService.search(propertyValuePattern, sources);
    }

    @Override public Collection<AnnotationSummary> search(String propertyType,
                                                          String propertyValuePattern,
                                                          URI... sources) {
        return _annotatationSummarySearchService.search(propertyType, propertyValuePattern, sources);
    }

    @Override
    public Collection<AnnotationSummary> searchByPrefix(String propertyValuePrefix, URI... sources) {
        return _annotatationSummarySearchService.searchByPrefix(propertyValuePrefix, sources);
    }

    @Override
    public Collection<AnnotationSummary> searchByPrefix(String propertyType, String propertyValuePrefix, URI... sources) {
        return _annotatationSummarySearchService.searchByPrefix(propertyType, propertyValuePrefix, sources);
    }

    @Override public Collection<AnnotationSummary> searchBySemanticTags(String... semanticTagShortnames) {
        return _annotatationSummarySearchService.searchBySemanticTags(semanticTagShortnames);
    }

    @Override public Collection<AnnotationSummary> searchBySemanticTags(URI... semanticTags) {
        return _annotatationSummarySearchService.searchBySemanticTags(semanticTags);
    }

    @Override public Collection<AnnotationSummary> searchByPreferredSources(String propertyValuePattern,
                                                                            List<URI> preferredSources,
                                                                            URI... requiredSources) {
        return _annotatationSummarySearchService.searchByPreferredSources(propertyValuePattern,
                                                                          preferredSources,
                                                                          requiredSources);
    }

    @Override public Collection<AnnotationSummary> searchByPreferredSources(String propertyType,
                                                                            String propertyValuePattern,
                                                                            List<URI> preferredSources,
                                                                            URI... requiredSources) {
        return _annotatationSummarySearchService.searchByPreferredSources(propertyType,
                                                                          propertyValuePattern,
                                                                          preferredSources,
                                                                          requiredSources);
    }

    @Override protected void doInitialization() throws Exception {
        // do nothing by default
    }

    @Override protected void doTermination() throws Exception {
        // do nothing by default
    }
}
