package uk.ac.ebi.fgpt.zooma.service;

import uk.ac.ebi.fgpt.zooma.datasource.AnnotationDAO;
import uk.ac.ebi.fgpt.zooma.datasource.PropertyDAO;
import uk.ac.ebi.fgpt.zooma.exception.ZoomaUpdateException;
import uk.ac.ebi.fgpt.zooma.model.Annotation;
import uk.ac.ebi.fgpt.zooma.model.Property;

import java.net.URI;
import java.util.Collection;

/**
 * A property service that uses an implementation of {@link uk.ac.ebi.fgpt.zooma.datasource.PropertyDAO} to retrieve
 * property instances.
 *
 * @author Tony Burdett
 * @date 03/04/12
 */
public class DAOBasedPropertyService extends AbstractShortnameResolver implements PropertyService {
    private AnnotationService annotationService;

    private PropertyDAO propertyDAO;
    private AnnotationDAO annotationDAO;

    public AnnotationService getAnnotationService() {
        return annotationService;
    }

    public void setAnnotationService(AnnotationService annotationService) {
        this.annotationService = annotationService;
    }

    public PropertyDAO getPropertyDAO() {
        return propertyDAO;
    }

    public void setPropertyDAO(PropertyDAO propertyDAO) {
        this.propertyDAO = propertyDAO;
    }

    public AnnotationDAO getAnnotationDAO() {
        return annotationDAO;
    }

    public void setAnnotationDAO(AnnotationDAO annotationDAO) {
        this.annotationDAO = annotationDAO;
    }

    @Override public Collection<Property> getProperties() {
        return getPropertyDAO().read();
    }

    @Override public Collection<Property> getProperties(int limit, int start) {
        return getPropertyDAO().read(limit, start);
    }

    @Override public Property getProperty(String shortname) {
        return getProperty(getURIFromShortname(shortname));
    }

    @Override public Property getProperty(URI uri) {
        return getPropertyDAO().read(uri);
    }

    @Override public Collection<Property> getMatchedTypedProperty(String type, String value) {
        return getPropertyDAO().readByTypeAndValue(type, value);
    }

    @Override public Property getMatchedUntypedProperty(String value) {
        return getPropertyDAO().readByValue(value);
    }

    @Override public Collection<Property> getMatchedTypedProperties(String type) {
        return getPropertyDAO().readByType(type);
    }

    @Override public Property saveProperty(Property property) {
        if (property.getURI() != null && getPropertyDAO().read(property.getURI()) != null) {
            getPropertyDAO().update(property);
        }
        else {
            getPropertyDAO().create(property);
        }
        return property;
    }
}
