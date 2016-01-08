package uk.ac.ebi.fgpt.zooma.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import uk.ac.ebi.fgpt.zooma.access.SourceFilteredEndpoint;
import uk.ac.ebi.fgpt.zooma.access.Zooma;
import uk.ac.ebi.fgpt.zooma.io.ZOOMAReportRenderer;
import uk.ac.ebi.fgpt.zooma.model.AnnotationPrediction;
import uk.ac.ebi.fgpt.zooma.model.Property;
import uk.ac.ebi.fgpt.zooma.model.SimpleTypedProperty;
import uk.ac.ebi.fgpt.zooma.model.SimpleUntypedProperty;
import uk.ac.ebi.fgpt.zooma.model.TypedProperty;
import uk.ac.ebi.fgpt.zooma.search.ZOOMASearchTimer;
import uk.ac.ebi.fgpt.zooma.service.OntologyService;
import uk.ac.ebi.fgpt.zooma.util.OntologyLabelMapper;

import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A controller stereotype that provides a REST-API endpoint to run a ZOOMA search over a series of properties.
 * <p/>
 * This class is essentially an alternative to {@link uk.ac.ebi.fgpt.zooma.search.ZOOMASearchClient}, optimized to run
 * searches in a web-application context instead of standalone application.
 *
 * @author Tony Burdett
 * @date 02/04/13
 */
@Controller
@RequestMapping("/services/map")
public class ZoomaMappingController extends SourceFilteredEndpoint {
    private final Zooma zooma;
    private final OntologyService ontologyService;

    private final int searchTimeout;

    private Logger log = LoggerFactory.getLogger(getClass());

    protected Logger getLog() {
        return log;
    }

    @Autowired
    public ZoomaMappingController(Zooma zooma, OntologyService ontologyService) {
        this.zooma = zooma;
        this.ontologyService = ontologyService;
        this.searchTimeout = 10;
    }

    @RequestMapping(method = RequestMethod.POST, consumes = "application/json")
    public @ResponseBody String requestMapping(@RequestParam(required = false, defaultValue = "") String filter,
                                               @RequestBody ZoomaMappingRequest request,
                                               HttpSession session)
            throws IOException {
        List<Property> properties = parseMappingRequest(request);
        session.setAttribute("properties", properties);
        session.setAttribute("progress", 0);

        URI[] requiredSources = parseRequiredSourcesFromFilter(filter);
        List<URI> preferredSources = parsePreferredSourcesFromFilter(filter);

        searchZOOMA(properties, requiredSources, preferredSources, session);
        return "Mapping request of " + properties.size() + " properties was successfully received";
    }

    @RequestMapping(value = "/sample", method = RequestMethod.GET)
    public @ResponseBody List<Property> getSampleData() {
        List<Property> properties = new ArrayList<>();
        properties.add(new SimpleTypedProperty("compound", "broccoli"));
        properties.add(new SimpleTypedProperty("disease state", "lung adenocarcinoma"));
        properties.add(new SimpleTypedProperty("cell type", "CD4-positive"));
        return properties;
    }

    @RequestMapping(value = "/test", method = RequestMethod.GET)
    public @ResponseBody String test(HttpSession session) throws IOException {
        List<Property> properties = getSampleData();
        URI[] requiredSources = new URI[]{};
        List<URI> preferredSources = new ArrayList<>();
        session.setAttribute("properties", properties);
        session.setAttribute("progress", 0f);

        searchZOOMA(properties, requiredSources, preferredSources, session);
        return "Doing ZOOMA search";
    }

    @RequestMapping(value = "/status", method = RequestMethod.GET)
    public @ResponseBody float checkMappingStatus(HttpSession session) {
        float progress;
        if (session.getAttribute("progress") != null) {
            //noinspection RedundantCast
            float done = (float) ((Integer) session.getAttribute("progress"));
            float total = (float) ((List) session.getAttribute("properties")).size();
            if (done > 0) {
                progress = done / total;
            }
            else {
                progress = 0;
            }
        }
        else {
            progress = 0;
        }
        getLog().debug("Progress = " + progress);
        return progress;
    }

    @RequestMapping(method = RequestMethod.GET, produces = "text/plain")
    public @ResponseBody String getMappingReport(HttpSession session) {
        getLog().debug("Getting result");
        Object result = session.getAttribute("result");
        if (checkMappingStatus(session) == 1f) {
            while (result == null) {
                getLog().debug("Pending result, report rendering?");
                synchronized (this) {
                    try {
                        this.wait(500);
                    }
                    catch (InterruptedException e) {
                        getLog().error("Interrupted", e);
                    }
                }
                result = session.getAttribute("result");
            }
        }
        if (result != null && !result.toString().isEmpty()) {
            getLog().debug("Result: " + result);
            return result.toString();
        }
        else {
            getLog().debug("Result is empty");
            return "";
        }
    }

    @RequestMapping(method = RequestMethod.GET, produces = "application/json", params = "json")
    public @ResponseBody Map<String, Object> getMappingResult(HttpSession session) {
        Map<String, Object> response = new HashMap<>();

        Exception exception = (Exception) session.getAttribute("exception");
        if (exception == null) {
            response.put("status", "OK");
        }
        else {
            response.put("status", exception.getMessage());
        }

        String report = getMappingReport(session);
        response.put("data", parseMappingReport(report));
        return response;
    }

    @RequestMapping(value = "/reset", method = RequestMethod.GET)
    public @ResponseBody String resetSession(HttpSession session) {
        try {
            session.invalidate();
            return "Your mapping session was cleared successfully";
        }
        catch (Exception e) {
            return "Your mapping session could not be reset";
        }
    }

    @ResponseStatus(HttpStatus.REQUEST_TIMEOUT)
    @ExceptionHandler(IllegalStateException.class)
    public @ResponseBody String handleStateException(IllegalStateException e) {
        return "Session in conflict - your session may have timed out whilst results were being generated " +
                "(" + e.getMessage() + ")";
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public @ResponseBody String handleException(Exception e) {
        getLog().error("Unexpected exception", e);
        return "The server encountered a problem it could not recover from " +
                "(" + e.getMessage() + ")";
    }

    private List<Property> parseMappingRequest(ZoomaMappingRequest request) {
        List<Property> result = new ArrayList<>();
        for (ZoomaMappingRequestItem item : request) {
            if (item.getPropertyType() != null) {
                result.add(new SimpleTypedProperty(item.getPropertyType(), item.getPropertyValue()));
            }
            else {
                result.add(new SimpleUntypedProperty(item.getPropertyValue()));
            }
        }
        return result;
    }

    private List<String[]> parseMappingReport(String report) {
        // create results
        List<String[]> results = new ArrayList<>();

        // read and parse mapping report
        BufferedReader reader = new BufferedReader(new StringReader(report));
        String line;
        try {
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                // skip first 6 lines, they're header
                if (lineNumber > 7) {
                    String[] values = line.split("\\t", -1);
                    results.add(values);
                }
                lineNumber++;
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return results;
    }

    private void searchZOOMA(final List<Property> properties,
                             final URI[] requiredSources,
                             final List<URI> preferredSources,
                             final HttpSession session) {
        getLog().info("Searching ZOOMA for mappings...");

        // create a timer to time search tasks
        final ZOOMASearchTimer timer = new ZOOMASearchTimer(properties.size()).start();

        // create a map to hold results
        final Map<Property, List<AnnotationPrediction>> annotationPredictions =
                Collections.synchronizedMap(new HashMap<Property, List<AnnotationPrediction>>());

        // start searching using a single thread executor, a completion service, and our Zooma instance
        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final CompletionService<Property> completionService = new ExecutorCompletionService<>(executorService);
        for (final Property property : properties) {
            // simple unit of work to perform the zooma search and update annotations with results
            if (getLog().isTraceEnabled()) {
                getLog().trace("Submitting next search, for " + property);
            }

            completionService.submit(new Callable<Property>() {
                @Override
                public Property call() throws Exception {
                    if (property instanceof TypedProperty) {
                        String propertyType = ((TypedProperty) property).getPropertyType();
                        String propertyValue = property.getPropertyValue();
                        if (preferredSources.isEmpty() && requiredSources.length == 0) {
                            annotationPredictions.put(property, zooma.annotate(propertyValue, propertyType));
                        }
                        else {
                            annotationPredictions.put(property, zooma.annotate(propertyValue,
                                                                               propertyType,
                                                                               preferredSources,
                                                                               requiredSources));
                        }
                    }
                    else {
                        String propertyValue = property.getPropertyValue();
                        if (preferredSources.isEmpty() && requiredSources.length == 0) {
                            annotationPredictions.put(property, zooma.annotate(propertyValue));
                        }
                        else {
                            annotationPredictions.put(property, zooma.annotate(propertyValue,
                                                                               preferredSources,
                                                                               requiredSources));
                        }
                    }
                    return property;
                }
            });
        }

        monitorSearchCompletion(executorService,
                                completionService,
                                timer,
                                properties,
                                annotationPredictions,
                                session);
    }

    private void monitorSearchCompletion(final ExecutorService executorService,
                                         final CompletionService<Property> completionService,
                                         final ZOOMASearchTimer timer,
                                         final List<Property> properties,
                                         final Map<Property, List<AnnotationPrediction>> annotationPredictions,
                                         final HttpSession session) {
        // create a thread to run until all ZOOMA searches have finished, then update session
        new Thread(new Runnable() {
            @Override public void run() {
                // collate results
                int failedCount = 0;
                for (int i = 0; i < properties.size(); i++) {
                    if (getLog().isTraceEnabled()) {
                        getLog().trace("Attempting to get search result " + i + "/" + properties.size() + "...");
                    }

                    try {
                        // wait for next task to complete - each search gets timeout seconds max to prevent stalling
                        Future<Property> f = completionService.poll(searchTimeout, TimeUnit.SECONDS);
                        if (f == null) {
                            failedCount++;
                            getLog().error("A search job failed to complete in " + searchTimeout + " seconds - " +
                                                   "there are " + failedCount + " fails now.");
                        }
                        else {
                            try {
                                f.get(searchTimeout, TimeUnit.SECONDS);
                            }
                            catch (TimeoutException e) {
                                failedCount++;
                                getLog().error("Results of a search job were not available in " + searchTimeout +
                                                       " seconds - there are " + failedCount + " fails now.");
                            }
                        }
                    }
                    catch (InterruptedException e) {
                        failedCount++;
                        getLog().error("A job was interrupted whilst waiting for completion - " +
                                               "there are " + failedCount + " fails now.  Error was:\n", e);
                    }
                    catch (ExecutionException e) {
                        failedCount++;
                        getLog().error("A job failed to execute - there are " + failedCount + " fails now.  " +
                                               "Error was:\n", e.getCause());
                    }
                    catch (Exception e) {
                        failedCount++;
                        getLog().error("A job failed with an unexpected exception - " +
                                               "there are " + failedCount + " fails now.  Error was:\n", e);
                    }
                    finally {
                        // update timing stats
                        timer.completedNext();
                        session.setAttribute("progress", timer.getCompletedCount());
                        if (getLog().isTraceEnabled()) {
                            getLog().trace(timer.getCompletedCount() + " searches have now completed");
                        }
                    }
                }

                renderReport(properties, annotationPredictions, failedCount, timer, session);

                // and cleanup
                getLog().debug("Shutting down executor service...");
                executorService.shutdown();
                try {
                    if (executorService.awaitTermination(2, TimeUnit.MINUTES)) {
                        getLog().debug("Executor service shutdown gracefully.");
                    }
                    else {
                        int abortedTasks = executorService.shutdownNow().size();
                        getLog().warn("Executor service forcibly shutdown. " + abortedTasks + " tasks were aborted");
                    }
                }
                catch (InterruptedException e) {
                    getLog().error("Executor service failed to shutdown cleanly", e);
                    throw new RuntimeException("Unable to cleanly shutdown ZOOMA.", e);
                }
            }
        }).start();
    }

    private void renderReport(List<Property> properties,
                              Map<Property, List<AnnotationPrediction>> annotationPredictions,
                              int failedCount,
                              ZOOMASearchTimer timer,
                              HttpSession session) {
        // render the report
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZOOMAReportRenderer renderer =
                new ZOOMAReportRenderer(new LabelMapper(ontologyService),
                                        out,
                                        out); // unmapped elements go in same report
        Map<Property, List<String>> propertyContexts = new HashMap<>();
        renderer.renderAnnotations(properties, propertyContexts, annotationPredictions);
        try {
            renderer.close();
            getLog().debug("ZOOMA search complete, results will be stored in a session attribute");
            session.setAttribute("progress", timer.getCompletedCount());
            session.setAttribute("result", out.toString());

            if (failedCount > 0) {
                session.setAttribute("exception", new RuntimeException(
                        "There were " + failedCount + " ZOOMA searches that encountered problems"));
            }
        }
        catch (IOException e) {
            session.setAttribute("exception", e);
        }
        getLog().info(
                "Successfully generated ZOOMA report for " + timer.getCompletedCount() + " searches," +
                        " HTTP session '" + session.getId() + "'");
    }

    private boolean hasTimedOut(long timeLastUpdated) {
        return timeLastUpdated > (System.currentTimeMillis() - (searchTimeout * 1000 * 60));
    }

    private class LabelMapper implements OntologyLabelMapper {
        private OntologyService ontologyService;

        private LabelMapper(OntologyService ontologyService) {
            this.ontologyService = ontologyService;
        }

        @Override
        public String getLabel(URI uri) {
            return ontologyService.getLabel(uri);
        }

        @Override
        public Collection<String> getSynonyms(URI uri) {
            return ontologyService.getSynonyms(uri);
        }

        @Override
        public URI getURI(String label) {
            throw new UnsupportedOperationException("This mapper does not support URI lookup from labels");
        }
    }
}
