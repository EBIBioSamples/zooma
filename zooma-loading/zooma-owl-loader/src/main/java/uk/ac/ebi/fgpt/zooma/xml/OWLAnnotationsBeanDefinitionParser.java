package uk.ac.ebi.fgpt.zooma.xml;

import org.semanticweb.owlapi.model.IRI;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.AbstractBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;
import uk.ac.ebi.fgpt.zooma.datasource.DefaultAnnotationFactory;
import uk.ac.ebi.fgpt.zooma.datasource.OWLAnnotationDAO;
import uk.ac.ebi.fgpt.zooma.datasource.OWLLoadingSession;
import uk.ac.ebi.fgpt.zooma.owl.AssertedOntologyLoader;
import uk.ac.ebi.fgpt.zooma.owl.ReasonedOntologyLoader;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * An implementation of Spring's BeanDefinitionParser class that generates a fully preconfigured OWLAnnotationDAO by
 * parsing a simple bean definition
 *
 * @author Tony Burdett
 * @date 23/05/13
 */
public class OWLAnnotationsBeanDefinitionParser extends AbstractBeanDefinitionParser {
    @Override
    protected boolean shouldGenerateId() {
        return true;
    }

    @Override
    protected AbstractBeanDefinition parseInternal(Element element, ParserContext parserContext) {
        // required (non-null) attributes
        String name = element.getAttribute("name");
        URI ontologyURI = URI.create(element.getAttribute("uri"));

        // optional attributes
        Resource ontologyResource = null;
        String loadFrom = element.getAttribute("loadFrom");
        if (StringUtils.hasText(loadFrom)) {
            ontologyResource = new DefaultResourceLoader().getResource(loadFrom);
        }

        Collection<URI> synonymURIs = null;
        List<Element> synonymElements = DomUtils.getChildElementsByTagName(element, "synonym");
        if (synonymElements != null) {
            synonymURIs = new HashSet<>();
            for (Element synonymElement : synonymElements) {
                String synonymURIStr = synonymElement.getAttribute("uri");
                if (StringUtils.hasText(synonymURIStr)) {
                    synonymURIs.add(URI.create(synonymURIStr));
                }
            }
        }

        Map<IRI, IRI> ontologyImportMappings = null;
        List<Element> importMappingElements = DomUtils.getChildElementsByTagName(element, "importMapping");
        if (importMappingElements != null) {
            ontologyImportMappings = new HashMap<>();
            for (Element importMappingElement : importMappingElements) {
                String uriStr = importMappingElement.getAttribute("uri");
                String loadFromStr = importMappingElement.getAttribute("loadFrom");
                ontologyImportMappings.put(IRI.create(uriStr), IRI.create(loadFromStr));
            }
        }

        URI exclusionClassURI = null;
        String exclusionClassURIStr = element.getAttribute("exclusionClassURI");
        if (StringUtils.hasText(exclusionClassURIStr)) {
            exclusionClassURI = URI.create(exclusionClassURIStr);
        }
        URI exclusionAnnotationURI = null;
        String exclusionAnnotationURIStr = element.getAttribute("exclusionAnnotationURI");
        if (StringUtils.hasText(exclusionAnnotationURIStr)) {
            exclusionAnnotationURI = URI.create(exclusionAnnotationURIStr);
        }
        boolean useReasoning = true;
        String useReasoningStr = element.getAttribute("useReasoning");
        if (StringUtils.hasText(useReasoningStr)) {
            useReasoning = Boolean.parseBoolean(useReasoningStr);
        }

        // generate and wire up classes...

        // OntologyLoader, e.g.
        //     <bean name="owlLoader" class="uk.ac.ebi.fgpt.zooma.owl.AssertedOntologyLoader" init-method="init">
        //         <property name="ontologyURI" value="http://www.ebi.ac.uk/efo" />
        //         <property name="ontologyResource" value="http://www.ebi.ac.uk/efo/efo.owl" />
        //         <property name="synonymURI" value="http://www.ebi.ac.uk/efo/alternative_term" />
        //     </bean>
        BeanDefinitionBuilder owlLoader;
        if (useReasoning) {
            owlLoader = BeanDefinitionBuilder.rootBeanDefinition(ReasonedOntologyLoader.class);
        }
        else {
            owlLoader = BeanDefinitionBuilder.rootBeanDefinition(AssertedOntologyLoader.class);
        }
        owlLoader.setInitMethodName("init");
        owlLoader.addPropertyValue("ontologyURI", ontologyURI);
        owlLoader.addPropertyValue("ontologyName", name);
        if (ontologyResource != null) {
            owlLoader.addPropertyValue("ontologyResource", ontologyResource);
        }
        if (synonymURIs != null) {
            owlLoader.addPropertyValue("synonymURIs", synonymURIs);
        }
        if (ontologyImportMappings != null) {
            owlLoader.addPropertyValue("ontologyImportMappings", ontologyImportMappings);
        }
        if (exclusionClassURI != null) {
            owlLoader.addPropertyValue("exclusionClassURI", exclusionClassURI);
        }
        if (exclusionAnnotationURI != null) {
            owlLoader.addPropertyValue("exclusionAnnotationURI", exclusionAnnotationURI);
        }
        parserContext.registerBeanComponent(new BeanComponentDefinition(owlLoader.getBeanDefinition(),
                                                                        name + "-owlLoader"));

        // OWLLoadingSession
        //    <bean name="owlLoadingSession" class="uk.ac.ebi.fgpt.zooma.datasource.OWLLoadingSession">
        //        <constructor-arg name="owlLoader" ref="owlLoader" />
        //    </bean>
        BeanDefinitionBuilder owlLoadingSession = BeanDefinitionBuilder.rootBeanDefinition(OWLLoadingSession.class);
        owlLoadingSession.addConstructorArgReference(name + "-owlLoader");
        parserContext.registerBeanComponent(new BeanComponentDefinition(owlLoadingSession.getBeanDefinition(),
                                                                        name + "-owlLoadingSession"));

        // OWLAnnotationFactory
        //     <bean name="owlAnnotationFactory" class="uk.ac.ebi.fgpt.zooma.datasource.OWLAnnotationFactory">
        //         <constructor-arg name="annotationLoadingSession" ref="owlLoadingSession" />
        //         <constructor-arg name="owlLoader" ref="owlLoader" />
        //     </bean>
        BeanDefinitionBuilder owlAnnotationFactory =
                BeanDefinitionBuilder.rootBeanDefinition(DefaultAnnotationFactory.class);
        owlAnnotationFactory.addConstructorArgReference(name + "-owlLoadingSession");
        parserContext.registerBeanComponent(new BeanComponentDefinition(owlAnnotationFactory.getBeanDefinition(),
                                                                        name + "-owlAnnotationFactory"));

        // and now to set up the owlAnnotationDAO...
        //     <bean name="owlAnnotationDAO" class="uk.ac.ebi.fgpt.zooma.datasource.OWLAnnotationDAO" init-method="init">
        //         <constructor-arg name="annotationFactory" ref="owlAnnotationFactory" />
        //         <constructor-arg name="owlLoader" ref="owlLoader" />
        //         <constructor-arg name="datasourceName" value="efo" />
        //     </bean>
        BeanDefinitionBuilder owlAnnotationDAO = BeanDefinitionBuilder.rootBeanDefinition(OWLAnnotationDAO.class);
        owlAnnotationDAO.setInitMethodName("init");
        owlAnnotationDAO.addConstructorArgReference(name + "-owlAnnotationFactory");
        owlAnnotationDAO.addConstructorArgReference(name + "-owlLoader");
        owlAnnotationDAO.addConstructorArgValue(name);
        AbstractBeanDefinition beanDefinition = owlAnnotationDAO.getBeanDefinition();
        parserContext.registerBeanComponent(new BeanComponentDefinition(beanDefinition,
                                                                        name + "-owlAnnotationDAO"));

        // return null as all bean definitions have been registered to context
        return null;
    }
}
