package uk.ac.ebi.fgpt.zooma.model;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * An implementation of an Annotation designed to be used by jackson to deserialize annotation requests.  You should NOT
 * use this implementation in code; objects are designed to be transient and in order to handle serialization demands
 * are also mutable.  If you want to code with annotations, using {@link uk.ac.ebi.fgpt.zooma.model.SimpleAnnotation} is
 * advisable.
 * <p/>
 * Note that because the Annotation interface defines a few methods for trailing the historical annotation graph without
 * standard "getters" and "setters" for these fields, requests do not neatly serialize to and from JSON.  As such, this
 * method defines orthodox getters and setters and then delegates to these methods from those methods that override the
 * interface.
 *
 * @author Tony Burdett
 * @date 15/07/13
 */
public class AnnotationRequest implements Annotation {
    private static final long serialVersionUID = 3852368256814720528L;

    private Collection<BiologicalEntity> annotatedBiologicalEntities = Collections.emptySet();
    private Property annotatedProperty;
    private Collection<URI> semanticTags = Collections.emptySet();
    private Collection<URI> replaces = Collections.emptySet();
    private Collection<URI> replacedBy = Collections.emptySet();
    private AnnotationProvenance provenance;
    private URI uri;

    // custom getters and setters for bona-fide "bean" credentials

    public void setURI(URI uri) {
       this.uri = uri;
    }

    @Override public URI getURI() {
        return uri;
    }

    public Collection<URI> getReplaces() {
        return replaces;
    }

//    public void setReplaces(Collection<URI> replaces) {
//        this.replaces = replaces;
//    }

    public Collection<URI> getReplacedBy() {
        return replacedBy;
    }

//    public void setReplacedBy(Collection<URI> replacedBy) {
//        this.replacedBy = replacedBy;
//    }

    // getters and setters that fit the interface

    @Override public Collection<BiologicalEntity> getAnnotatedBiologicalEntities() {
        return annotatedBiologicalEntities;
    }

    public void setAnnotatedBiologicalEntities(Collection<BiologicalEntity> annotatedBiologicalEntities) {
        this.annotatedBiologicalEntities = annotatedBiologicalEntities;
    }

    @Override public Property getAnnotatedProperty() {
        return annotatedProperty;
    }

    public void setAnnotatedProperty(Property annotatedProperty) {
        this.annotatedProperty = annotatedProperty;
    }

    @Override public Collection<URI> getSemanticTags() {
        return semanticTags;
    }

    public void setSemanticTags(Collection<URI> semanticTags) {
        this.semanticTags = semanticTags;
    }

    @Override public AnnotationProvenance getProvenance() {
        return provenance;
    }

    public void setProvenance(AnnotationProvenance provenance) {
        this.provenance = provenance;
    }

    @Override public void setReplacedBy(URI... replacedBy) {
        this.replacedBy = Arrays.asList(replacedBy);
    }

    @Override public void setReplaces(URI... replaces) {
        this.replaces = Arrays.asList(replaces);
    }


}
