package uk.ac.ebi.fgpt.zooma.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import uk.ac.ebi.fgpt.zooma.exception.ZoomaUpdateException;
import uk.ac.ebi.fgpt.zooma.model.Annotation;
import uk.ac.ebi.fgpt.zooma.model.AnnotationUpdate;
import uk.ac.ebi.fgpt.zooma.model.Property;
import uk.ac.ebi.fgpt.zooma.model.SimpleBiologicalEntity;
import uk.ac.ebi.fgpt.zooma.model.SimpleStudy;
import uk.ac.ebi.fgpt.zooma.model.SimpleTypedProperty;
import uk.ac.ebi.fgpt.zooma.model.SimpleUntypedProperty;
import uk.ac.ebi.fgpt.zooma.model.TypedProperty;
import uk.ac.ebi.fgpt.zooma.service.AnnotationSearchService;
import uk.ac.ebi.fgpt.zooma.service.AnnotationService;
import uk.ac.ebi.fgpt.zooma.service.DataLoadingService;
import uk.ac.ebi.fgpt.zooma.service.PropertyService;
import uk.ac.ebi.fgpt.zooma.util.Limiter;
import uk.ac.ebi.fgpt.zooma.util.Sorter;
import uk.ac.ebi.fgpt.zooma.util.URIUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * Search ZOOMA for {@link uk.ac.ebi.fgpt.zooma.model.Annotation}s matching the supplied property value prefix and,
 * optionally, types.
 * <p/>
 * This class is a high level convenience implementation for searching annotations.  It will work out of the box, but
 * requires configuration with underlying service implementations. It is also a controller 'stereotype' that can be used
 * to construct a REST API and offers an implementation of the google and freebase suggest API to offer search and
 * autocomplete functionality over ZOOMA annotations.
 * <p/>
 * For more information on the reconcilliation API, see <a href="http://code.google.com/p/google-refine/wiki/ReconciliationServiceApi">http://code.google.com/p/google-refine/wiki/ReconciliationServiceApi</a>.
 * This controller returns matching results using ZOOMA functionality behind the scenes.
 *
 * @author Tony Burdett
 * @date 08/03/12
 */
@Controller
@RequestMapping("/annotations")
public class ZoomaAnnotations {
    private AnnotationService annotationService;
    private AnnotationSearchService annotationSearchService;
    private DataLoadingService<Annotation> dataLoadingService;
    private PropertyService propertyService;

    private Sorter<Annotation> annotationSorter;
    private Limiter<Annotation> annotationLimiter;

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected Logger getLog() {
        return log;
    }

    @Autowired
    public ZoomaAnnotations(AnnotationService annotationService,
                            AnnotationSearchService annotationSearchService,
                            DataLoadingService<Annotation> dataLoadingService,
                            PropertyService propertyService,
                            Sorter<Annotation> annotationSorter,
                            Limiter<Annotation> annotationLimiter) {
        this.annotationService = annotationService;
        this.annotationSearchService = annotationSearchService;
        this.dataLoadingService = dataLoadingService;
        this.propertyService = propertyService;
        this.annotationSorter = annotationSorter;
        this.annotationLimiter = annotationLimiter;
    }

    public PropertyService getPropertyService() {
        return propertyService;
    }

    public DataLoadingService<Annotation> getDataLoadingService() {
        return dataLoadingService;
    }

    public AnnotationService getAnnotationService() {
        return annotationService;
    }

    public AnnotationSearchService getAnnotationSearchService() {
        return annotationSearchService;
    }

    public Sorter<Annotation> getAnnotationSorter() {
        return annotationSorter;
    }

    public Limiter<Annotation> getAnnotationLimiter() {
        return annotationLimiter;
    }

    public Collection<Annotation> fetch() {
        return fetch(100, 0, null, null, null, false);
    }

    @RequestMapping(method = RequestMethod.GET)
    public @ResponseBody Collection<Annotation> fetch(@RequestParam(value = "limit", required = false) Integer limit,
                                                      @RequestParam(value = "start", required = false) Integer start,
                                                      @RequestParam(value = "targetSourceUri",
                                                                    required = false) String targetSourceUri,
                                                      @RequestParam(value = "targetUri",
                                                                    required = false) String targetUri,
                                                      @RequestParam(value = "semanticTagUri",
                                                                    required = false) String semanticTagUri,
                                                      @RequestParam(value = "latestOnly",
                                                                    required = false,
                                                                    defaultValue = "false") boolean latest
    ) {

        if (targetSourceUri != null) {
            return latest ?
                    getLatestAnnotations(getAnnotationService().getAnnotationsByStudy(new SimpleStudy(URI.create(
                            targetSourceUri), null)))
                    : getAnnotationService().getAnnotationsByStudy(new SimpleStudy(URI.create(targetSourceUri), null));
        }
        if (targetUri != null) {
            return latest ?
                    getLatestAnnotations(getAnnotationService().getAnnotationsByBiologicalEntity(new SimpleBiologicalEntity(
                            URI.create(targetUri),
                            null)))
                    : getAnnotationService().getAnnotationsByBiologicalEntity(new SimpleBiologicalEntity(URI.create(
                    targetUri), null));
        }
        if (semanticTagUri != null) {
            return latest ?
                    getLatestAnnotations(getAnnotationService().getAnnotationsBySemanticTag(URI.create(semanticTagUri)))
                    : getAnnotationService().getAnnotationsBySemanticTag(URI.create(semanticTagUri));
        }

        if (start == null) {
            if (limit == null) {
                return latest ?
                        getLatestAnnotations(getAnnotationService().getAnnotations(100, 0))
                        : getAnnotationService().getAnnotations(100, 0);
            }
            else {
                return latest ?
                        getLatestAnnotations(getAnnotationService().getAnnotations(limit, 0))
                        : getAnnotationService().getAnnotations(limit, 0);
            }
        }
        else {
            if (limit == null) {
                return latest ?
                        getLatestAnnotations(getAnnotationService().getAnnotations(100, start))
                        : getAnnotationService().getAnnotations(100, start);
            }
            else {
                return latest ?
                        getLatestAnnotations(getAnnotationService().getAnnotations(limit, start))
                        : getAnnotationService().getAnnotations(limit, start);
            }
        }
    }

    /**
     * Annotation is considered to be the latest if it is not replaced by any other annotations
     *
     * @param annotations collection of annotations to filter
     * @return list of latest annotations
     */
    private Collection<Annotation> getLatestAnnotations(Collection<Annotation> annotations) {
        List<Annotation> filtered = new ArrayList<>();
        for (Annotation a : annotations) {
            if (a.getReplacedBy().isEmpty()) {
                filtered.add(a);
            }
        }
        return filtered;
    }

    /**
     * Retrieves an annotation with the given URI.
     *
     * @param shortAnnotationURI the shortened form of the URI of the annotation to fetch
     * @return the annotation with the given URI
     */
    @RequestMapping(value = "/{shortAnnotationURI}", method = RequestMethod.GET)
    public @ResponseBody Annotation fetch(@PathVariable String shortAnnotationURI) {
        URI annotationURI = URIUtils.getURI(shortAnnotationURI);
        getLog().debug("Fetching " + annotationURI);
        return getAnnotationService().getAnnotation(annotationURI);
    }

    public Collection<Annotation> query(String prefix) {
        getLog().trace("Querying for " + prefix);
        Collection<Annotation> allAnnotations = getAnnotationSearchService().searchByPrefix(prefix);
        return getAnnotationSorter().sort(allAnnotations);
    }

    public Collection<Annotation> query(String prefix, String type) {
        getLog().trace("Querying for " + prefix + ", " + type);
        Collection<Annotation> allAnnotations = getAnnotationSearchService().searchByPrefix(type, prefix);
        return getAnnotationSorter().sort(allAnnotations);
    }

    public Collection<Annotation> query(String prefix, String type, int limit, int start) {
        getLog().trace("Querying for " + prefix + ", " + type + ", " + limit + ", " + start);
        Collection<Annotation> allAnnotations = getAnnotationSearchService().searchByPrefix(type, prefix);
        List<Annotation> allAnnotationsList = getAnnotationSorter().sort(allAnnotations);
        return getAnnotationLimiter().limit(allAnnotationsList, limit, start);
    }

    @RequestMapping(method = RequestMethod.POST)
    public @ResponseBody DataLoadingService.Receipt loadAnnotations(@RequestBody Collection<Annotation> annotations) {
        return getDataLoadingService().load(annotations);
    }

    @RequestMapping(value = "/batchUpdate", method = RequestMethod.POST)
    public @ResponseBody DataLoadingService.Receipt updateAnnotations(
            @RequestBody AnnotationUpdate update,
            @RequestParam(value = "oldPropertyUriFilter", required = false) Collection<String> oldPropertyUris,
            @RequestParam(value = "semanticTagUriFilter", required = false) String semanticTagUri,
            @RequestParam(value = "studyUriFilter", required = false) String studyUri,
            @RequestParam(value = "dataSourceFilter", required = true) String datasoureUri
    ) throws ZoomaUpdateException {

        // todo - check datasource and that user is authorised to edit

        // check old property URI exists.
        Collection<Annotation> annotationsToUpdate = new HashSet<Annotation>();

        if (oldPropertyUris != null) {
            for (String oldPropertyUri : oldPropertyUris) {
                Property oldProperty = getPropertyService().getProperty(URI.create(oldPropertyUri));

                if (oldProperty != null) {
                    // update semantic tags for old properties annotated to the tag filter
                    if (semanticTagUri != null) {
                        for (Annotation annoByStudy : getLatestAnnotations(getAnnotationService().getAnnotationsBySemanticTag(
                                URI.create(semanticTagUri)))) {
                            if (annoByStudy.getAnnotatedProperty().equals(oldProperty)) {
                                if (annoByStudy.getProvenance().getSource().getURI().toString().equals(datasoureUri)) {
                                    annotationsToUpdate.add(annoByStudy);
                                }
                            }
                        }
                    }
                    else if (studyUri != null) {
                        // update all properties in this study with the new supplied info
                        for (Annotation annoByStudy : getLatestAnnotations(getAnnotationService().getAnnotationsByStudy(
                                new SimpleStudy(URI.create(studyUri), null)))) {
                            if (annoByStudy.getAnnotatedProperty().equals(oldProperty)) {
                                if (annoByStudy.getProvenance().getSource().getURI().toString().equals(datasoureUri)) {
                                    annotationsToUpdate.add(annoByStudy);
                                }
                            }
                        }
                    }
                    else {
                        // if a new property type and value is supplied update the old property to the new one
                        if (update.getPropertyType() != null && update.getPropertyValue() != null) {
                            for (Annotation annoByProp : getLatestAnnotations(getAnnotationService().getAnnotationsByProperty(
                                    oldProperty))) {
                                if (annoByProp.getProvenance().getSource().getURI().toString().equals(datasoureUri)) {
                                    annotationsToUpdate.add(annoByProp);
                                }
                            }
                        }
                        // if only a property type is supplied, get all the properties with that type and update them with the supplied type (e.g organimspart to organism_part)
                        // old property must already have a type
                        else if (update.getPropertyType() != null) {
                            if (oldProperty instanceof TypedProperty) {
                                String oldType = ((TypedProperty) oldProperty).getPropertyType();

                                //                                for (Property matchedProperty : getPropertyService().getMatchedTypedProperty(oldType, null)) {
                                for (Annotation annoByProp : getLatestAnnotations(getAnnotationService().getAnnotationsByProperty(
                                        new SimpleTypedProperty(oldType, null)))) {
                                    if (annoByProp.getProvenance()
                                            .getSource()
                                            .getURI()
                                            .toString()
                                            .equals(datasoureUri)) {
                                        annotationsToUpdate.add(annoByProp);
                                    }
                                }
                                //                                }

                            }
                        }
                        // otherwise we are updating the property value for all properties with the old property type(e.g. human to homo sapiens)
                        else if (update.getPropertyValue() != null) {
                            //                            for (Property matchedProperty : getPropertyService().getMatchedTypedProperty(null, oldProperty.getPropertyValue())) {
                            for (Annotation annoByProp : getLatestAnnotations(getAnnotationService().getAnnotationsByProperty(
                                    new SimpleUntypedProperty(update.getPropertyValue())))) {
                                if (annoByProp.getProvenance().getSource().getURI().toString().equals(datasoureUri)) {
                                    annotationsToUpdate.add(annoByProp);
                                }
                            }
                            //                            }
                        }
                        else {
                            // both update property type and value are null so preserve
                            for (Annotation annoByProp : getLatestAnnotations(getAnnotationService().getAnnotationsByProperty(
                                    oldProperty))) {
                                if (annoByProp.getProvenance().getSource().getURI().toString().equals(datasoureUri)) {
                                    annotationsToUpdate.add(annoByProp);
                                }
                            }
                        }

                    }
                }
                else {
                    throw new IllegalArgumentException(
                            "Failed to update property: No property found with URI '" + oldPropertyUri + "'.");
                }
            }

        }
        else if (semanticTagUri != null) {
            for (Annotation annoByStudy : getLatestAnnotations(getAnnotationService().getAnnotationsBySemanticTag(URI.create(
                    semanticTagUri)))) {
                if (annoByStudy.getProvenance().getSource().getURI().toString().equals(datasoureUri)) {
                    annotationsToUpdate.add(annoByStudy);
                }
            }
        }

        return getDataLoadingService().update(annotationsToUpdate, update);
    }
}

