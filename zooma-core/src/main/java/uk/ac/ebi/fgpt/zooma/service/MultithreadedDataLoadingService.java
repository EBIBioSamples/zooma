package uk.ac.ebi.fgpt.zooma.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import uk.ac.ebi.fgpt.zooma.concurrent.WorkloadScheduler;
import uk.ac.ebi.fgpt.zooma.concurrent.ZoomaThreadFactory;
import uk.ac.ebi.fgpt.zooma.datasource.SemanticallyEnrichedDAO;
import uk.ac.ebi.fgpt.zooma.datasource.ZoomaDAO;
import uk.ac.ebi.fgpt.zooma.io.ZoomaLoader;
import uk.ac.ebi.fgpt.zooma.model.Identifiable;
import uk.ac.ebi.fgpt.zooma.model.Update;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link DataLoadingService} for ZOOMA data items that uses worker threads to load data.
 * <p>
 * This implementation creates two {@link ExecutorService}s, one that operates on the DAO level and one operating on a
 * datasource (or collection of data items) level.  Calls to {@link #load()} will create one parallel task per
 * datasource, and each datasource generates a set of tasks that are blocked into chunks of annotations to keep memory
 * overhead down.  By default, blocks of 100,000 data items are loaded per task at any one time, although this can be
 * configured based on available resources and the performance of the underlying datasource.
 * <p>
 * Actual loading of data items is delegated to a supplied {@link ZoomaLoader}; this service sets up the infrastructure
 * to do invocations on the loader in parallel.  By default there are a 4 worker threads available for datasource
 * loading, and 32 worker threads available for blocks of data within each datasource (giving a maximum load of 128
 * threads).
 * <p>
 * Note that, unlike all other services in ZOOMA, this service is enabled for Spring autowiring on {@link
 * #setZoomaDAOs(java.util.Collection)}.  This means it is possible to automatically discover declared DAOs from
 * anywhere on the classpath, providing the minimal spring config is supplied in the jar file
 * <p>
 * If you intend to use this autowiring mechanism to load into zooma, you should create spring configuration files to
 * set up your {@link ZoomaDAO} implementations and name these files, by convention, zooma-annotation-dao.xml.  This
 * service will then pick up these implementations and autowire them into this service, before using them to extract and
 * convert data into ZOOMA.
 * <p>
 * Note that if you wish to suppress DAOs being used in this way (for example, when compositing multiple DAOs) then you
 * should add "autowire-candidate=false" to those DAOs that should not be automatically discovered.
 *
 * @author Tony Burdett
 * @date 11/06/13
 */
public class MultithreadedDataLoadingService<T extends Identifiable> implements DataLoadingService<T> {
    private final ExecutorService daoExecutor;
    private final ExecutorService loadExecutor;

    private final ReceiptService receiptService;

    private ZoomaLoader<T> zoomaLoader;
    private Collection<ZoomaDAO<T>> zoomaDAOs = Collections.emptySet();

    private int maxCount;
    private int blockSize = 100_000;

    private Logger log = LoggerFactory.getLogger(getClass());

    protected Logger getLog() {
        return log;
    }

    public MultithreadedDataLoadingService() {
        this(4, 32);
    }

    public MultithreadedDataLoadingService(int numberOfDatasourceThreads,
                                           int numberOfLoaderThreads) {
        this.daoExecutor = Executors.newFixedThreadPool(numberOfDatasourceThreads,
                                                        new ZoomaThreadFactory("ZOOMA-DAO"));
        this.loadExecutor = Executors.newFixedThreadPool(numberOfLoaderThreads,
                                                         new ZoomaThreadFactory("ZOOMA-Loader"));

        this.receiptService = new InMemoryReceiptService();
    }

    public ZoomaLoader<T> getZoomaLoader() {
        return zoomaLoader;
    }

    public void setZoomaLoader(ZoomaLoader<T> zoomaLoader) {
        this.zoomaLoader = zoomaLoader;
    }

    @Autowired(required = false)
    public void setZoomaDAOs(Collection<ZoomaDAO<T>> zoomaDAOs) {
        this.zoomaDAOs = zoomaDAOs;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public void setBlockSize(int blockSize) {
        this.blockSize = blockSize;
    }

    public void shutdown() {
        getLog().info("Shutting down " + getClass().getSimpleName() + "...");
        daoExecutor.shutdown();
        loadExecutor.shutdown();
        getLog().info("Shut down " + getClass().getSimpleName() + " OK.");
    }


    @Override
    public Collection<ZoomaDAO<T>> getAvailableDatasources() {
        return zoomaDAOs;
    }

    @Override
    public void addDatasource(ZoomaDAO<T> datasource) {
        this.zoomaDAOs.add(datasource);
    }

    @Override
    public Receipt load() {
        // check the relevant executor service is alive
        if (daoExecutor.isShutdown()) {
            throw new IllegalStateException("Cannot load data - DAO-based loading services have been shutdown");
        }

        // check there are DAOs to load from
        if (getAvailableDatasources() == null || getAvailableDatasources().size() == 0) {
            String msg = "No datasources could be detected - there is nothing for ZOOMA to load.";
            getLog().error(msg);
            throw new NullPointerException(msg);
        }
        else {
            getLog().debug("Loading data from " + getAvailableDatasources().size() + " datasources");
        }

        // create a receipt to track nested loads
        final CompositingReceipt receipt = new CompositingReceipt("All Available", LoadType.LOAD_ALL);
        receiptService.registerReceipt(receipt);

        // create a counter to keep track of the number of loads that have been invoked
        final AtomicInteger counter = new AtomicInteger(1);

        // get the security context
        final SecurityContext ctx = SecurityContextHolder.getContext();

        // create a workload scheduler to schedule parallel loader tasks by DAO
        List<ZoomaDAO<T>> zoomaDAOList = new ArrayList<>();
        zoomaDAOList.addAll(getAvailableDatasources());
        final List<ZoomaDAO<T>> syncedAnnotationDAOs = Collections.synchronizedList(zoomaDAOList);
        WorkloadScheduler scheduler =
                new WorkloadScheduler(daoExecutor, syncedAnnotationDAOs.size(), "zooma loading", true) {
                    @Override
                    protected void executeTask(int iteration) throws Exception {
                        try {
                            // set security context
                            SecurityContextHolder.setContext(ctx);

                            // do next load
                            ZoomaDAO<T> dao = syncedAnnotationDAOs.get(iteration - 1);
                            getLog().debug("Delegating next load task for DAO '" + dao.getDatasourceName() + "' " +
                                                   "(iteration " + iteration + ")");
                            Receipt r;
                            try {
                                r = load(dao);
                            }
                            catch (Exception e) {
                                getLog().error("Scheduling of loading tasks for " + dao.getDatasourceName() +
                                                       " failed (" + e.getMessage() + ")");
                                r = new SchedulingFailedReceipt(dao.getDatasourceName(), LoadType.LOAD_DATASOURCE, e);
                            }

                            // add next receipt to compositing receipt tracker
                            receipt.addNextReceipt(r);

                            // is this last iteration? if so, finish
                            if (counter.getAndIncrement() == syncedAnnotationDAOs.size()) {
                                getLog().debug(
                                        "Finished scheduling load task for DAO '" + dao.getDatasourceName() + "' " +
                                                "(iteration " + iteration + "). This is the last task."
                                );
                                receipt.finish();
                            }
                        }
                        finally {
                            // clear security context
                            SecurityContextHolder.clearContext();
                        }
                    }
                };

        // start up the scheduler
        scheduler.start();
        return receipt;
    }

    @Override
    public Receipt load(final ZoomaDAO<T> datasource) {
        // check the relevant executor service is alive
        if (loadExecutor.isShutdown()) {
            throw new IllegalStateException("Cannot load data - loading services have been shutdown");
        }

        getLog().info("Retrieving data items from " + datasource.getDatasourceName() + " using " +
                              datasource.getClass().getSimpleName());

        Receipt receipt;
        try {
            final WorkloadScheduler scheduler;
            final int iterations;
            final int blockSize;

            int count = -1;
            try {
                count = datasource.count();
            }
            catch (UnsupportedOperationException e) {
                getLog().warn(datasource.getDatasourceName() + " does not support count() operation, " +
                                      "loading will take place in one single round");
            }

            if (count != -1) {
                int total = getMaxCount() > 0 && getMaxCount() < count ? getMaxCount() : count;

                // create a workload scheduler to queue load tasks for this DAO
                iterations = (total / getBlockSize()) + (total % getBlockSize() > 0 ? 1 : 0);
                blockSize = getBlockSize();
                getLog().debug("Scheduling workload for " + datasource.getDatasourceName() + ".  " +
                                       "Loading will take place in " + iterations + " rounds " +
                                       "of " + getBlockSize() + " data items each.");
            }
            else {
                iterations = 1;
                blockSize = -1;
            }

            // get the security context
            final SecurityContext ctx = SecurityContextHolder.getContext();

            if (blockSize != -1) {
                scheduler = new WorkloadScheduler(loadExecutor, iterations, datasource.getDatasourceName()) {
                    @Override
                    protected void executeTask(int iteration) throws Exception {
                        // translate iteration count back to start value for DAO query
                        int taskStart = (iteration - 1) * blockSize;

                        // fetch items
                        try {
                            // set security context
                            SecurityContextHolder.setContext(ctx);

                            getLog().debug(
                                    "Fetching data items for " + datasource.getDatasourceName() + ", " +
                                            "round " + iteration + "/" + iterations + ", " +
                                            "executing in " + Thread.currentThread().getName()
                            );
                            Collection<T> items = datasource.read(blockSize, taskStart);
                            getZoomaLoader().load(datasource.getDatasourceName(), items);

                            // also, if the DAO is semantically enriched, load supplementary data
                            if (datasource instanceof SemanticallyEnrichedDAO) {
                                InputStream rdfIn = ((SemanticallyEnrichedDAO) datasource).getSupplementaryRDFStream();
                                getZoomaLoader().loadSupplementaryData(datasource.getDatasourceName(), rdfIn);
                            }
                        }
                        catch (UnsupportedOperationException e) {
                            getLog().warn(datasource.getDatasourceName() + " does not support read(size, start).  " +
                                                  "No annotations will be loaded.");
                        }
                        finally {
                            // clear security context
                            SecurityContextHolder.clearContext();
                        }
                    }
                };
            }
            else {
                scheduler = new WorkloadScheduler(loadExecutor, iterations, datasource.getDatasourceName()) {
                    @Override
                    protected void executeTask(int iteration) throws Exception {
                        // fetch items
                        try {
                            // set security context
                            SecurityContextHolder.setContext(ctx);

                            getLog().debug(
                                    "Fetching data items for " + datasource.getDatasourceName() +
                                            " in a single round, " +
                                            "executing in " + Thread.currentThread().getName()
                            );
                            Collection<T> items = datasource.read();
                            getZoomaLoader().load(datasource.getDatasourceName(), items);
                        }
                        catch (UnsupportedOperationException e) {
                            getLog().warn(datasource.getDatasourceName() + " does not support read().  " +
                                                  "No annotations will be loaded.");
                        }
                        finally {
                            // clear security context
                            SecurityContextHolder.clearContext();
                        }
                    }
                };
            }

            // create a receipt
            receipt = new SingleWorkloadReceipt(datasource.getDatasourceName(), LoadType.LOAD_DATASOURCE, scheduler);
            receiptService.registerReceipt(receipt);

            // start up the scheduler
            scheduler.start();
        }
        catch (Exception e) {
            // failed to schedule this load task, add a failed receipt
            getLog().error("Scheduling of loading tasks for " + datasource.getDatasourceName() +
                                   " failed (" + e.getMessage() + ")");
            receipt = new SchedulingFailedReceipt(datasource.getDatasourceName(), LoadType.LOAD_DATASOURCE, e);
            receiptService.registerReceipt(receipt);
        }

        return receipt;
    }

    @Override
    public Receipt load(final Collection<T> dataItems) {
        return load(dataItems, Integer.toString(dataItems.hashCode()));
    }

    @Override
    public Receipt update(final Collection<T> dataItems, final Update<T> update) {
        // check the relevant executor service is alive
        if (loadExecutor.isShutdown()) {
            throw new IllegalStateException("Cannot update data - loading services have been shutdown");
        }

        Receipt receipt;
        try {
            getLog().info("Updating " + dataItems.size() + " supplied data items");

            int total = getMaxCount() > 0 ? getMaxCount() : dataItems.size();

            // get the security context
            final SecurityContext ctx = SecurityContextHolder.getContext();

            // create a workload scheduler to queue load tasks for tihs DAO
            final int iterations = (total / getBlockSize()) + (total % getBlockSize() > 0 ? 1 : 0);
            getLog().debug(
                    "Scheduling workload for updating annotations will take place in " + iterations + " rounds " +
                            "of " + getBlockSize() + " data items each.");
            final WorkloadScheduler scheduler =
                    new WorkloadScheduler(loadExecutor, iterations, "zooma-update") {
                        @Override
                        protected void executeTask(int iteration) throws Exception {
                            try {
                                // set security context
                                SecurityContextHolder.setContext(ctx);

                                // load data items
                                getLog().debug(
                                        "Updating data items for round " + iteration + "/" + iterations + ", " +
                                                "executing in " + Thread.currentThread().getName()
                                );
                                getZoomaLoader().update(dataItems, update);
                            }
                            finally {
                                // clear security context
                                SecurityContextHolder.clearContext();
                            }
                        }
                    };

            // create a receipt
            receipt = new SingleWorkloadReceipt("zooma-update", LoadType.LOAD_DATAITEMS, scheduler);
            receiptService.registerReceipt(receipt);

            // start up the scheduler
            scheduler.start();
        }
        catch (Exception e) {
            // failed to schedule this load task, add a failed receipt
            getLog().error("Scheduling of loading tasks for 'zooma-update' failed (" + e.getMessage() + ")");
            receipt = new SchedulingFailedReceipt("zooma-update", LoadType.LOAD_DATASOURCE, e);
            receiptService.registerReceipt(receipt);
        }
        return receipt;
    }

    @Override public Receipt load(final Collection<T> dataItems, final String datasetName) {
        // check the relevant executor service is alive
        if (loadExecutor.isShutdown()) {
            throw new IllegalStateException("Cannot load data - loading services have been shutdown");
        }

        Receipt receipt;
        try {
            getLog().info("Loading " + dataItems.size() + " supplied data items, " +
                                  "the assigned dataset name is " + datasetName);

            int total = getMaxCount() > 0 ? getMaxCount() : dataItems.size();

            // get the security context
            final SecurityContext ctx = SecurityContextHolder.getContext();

            // create a workload scheduler to queue load tasks for tihs DAO
            final int iterations = (total / getBlockSize()) + (total % getBlockSize() > 0 ? 1 : 0);
            getLog().debug("Scheduling workload for " + datasetName + ".  " +
                                   "Loading will take place in " + iterations + " rounds " +
                                   "of " + getBlockSize() + " data items each.");
            final WorkloadScheduler scheduler =
                    new WorkloadScheduler(loadExecutor, iterations, datasetName) {
                        @Override
                        protected void executeTask(int iteration) throws Exception {
                            try {
                                // set security context
                                SecurityContextHolder.setContext(ctx);

                                // load data items
                                getLog().debug(
                                        "Loading data items for " + datasetName + ", " +
                                                "round " + iteration + "/" + iterations + ", " +
                                                "executing in " + Thread.currentThread().getName()
                                );
                                getZoomaLoader().load(datasetName, dataItems);
                            }
                            finally {
                                // clear security context
                                SecurityContextHolder.clearContext();
                            }
                        }
                    };

            // create a receipt
            receipt = new SingleWorkloadReceipt(datasetName, LoadType.LOAD_DATAITEMS, scheduler);
            receiptService.registerReceipt(receipt);

            // start up the scheduler
            scheduler.start();
        }
        catch (Exception e) {
            // failed to schedule this load task, add a failed receipt
            getLog().error("Scheduling of loading tasks for " + datasetName + " failed (" + e.getMessage() + ")");
            receipt = new SchedulingFailedReceipt("zooma-update", LoadType.LOAD_DATASOURCE, e);
            receiptService.registerReceipt(receipt);
        }
        return receipt;
    }

    @Override
    public String getServiceStatus() {
        if (daoExecutor.isShutdown() || loadExecutor.isShutdown()) {
            if (daoExecutor.isTerminated() && loadExecutor.isTerminated()) {
                return getClass().getSimpleName() + " has been completely shutdown";
            }
            else {
                return getClass().getSimpleName() + " is shutting down";
            }
        }
        else {
            return getClass().getSimpleName() + " is running";
        }
    }

    @Override public ReceiptStatus getReceiptStatus(String receiptID) {
        return receiptService.getReceiptStatus(receiptID);
    }

    private final AtomicInteger receiptNumber = new AtomicInteger(1);

    private abstract class AbstractReceipt implements Receipt {
        private final String id;
        private final String datasourceName;
        private final LoadType loadType;
        private final Date submissionDate;
        private Date completionDate;

        private AbstractReceipt(String datasourceName, LoadType loadType) {
            this.id = Integer.toString(receiptNumber.getAndIncrement());
            this.datasourceName = datasourceName;
            this.loadType = loadType;
            this.submissionDate = new Date();
            this.completionDate = null;
        }

        @Override
        public String getID() {
            return id;
        }

        @Override
        public String getDatasourceName() {
            return datasourceName;
        }

        @Override
        public LoadType getLoadType() {
            return loadType;
        }

        @Override
        public Date getSubmissionDate() {
            return submissionDate;
        }

        @Override
        public Date getCompletionDate() {
            return completionDate;
        }

        @Override public String toString() {
            return "Receipt {\n" +
                    "\tid = '" + id + "',\n" +
                    "\tdatasourceName = '" + datasourceName + "',\n" +
                    "\tloadType = " + loadType + "',\n" +
                    "\tsubmissionDate = " + submissionDate.toString() + "\n}";
        }
    }

    private class SchedulingFailedReceipt extends AbstractReceipt {
        private final Throwable throwable;

        private SchedulingFailedReceipt(String datasourceName, LoadType loadType, Throwable throwable) {
            super(datasourceName, loadType);
            this.throwable = throwable;
        }

        @Override public void waitUntilCompletion() throws InterruptedException {
            throw new RuntimeException("A " + getDatasourceName() + " loading task failed", throwable);
        }
    }

    private class SingleWorkloadReceipt extends AbstractReceipt {
        // this receipt should track scheduled work
        private final WorkloadScheduler scheduler;

        private SingleWorkloadReceipt(String datasourceName, LoadType loadType, WorkloadScheduler scheduler) {
            super(datasourceName, loadType);
            this.scheduler = scheduler;
        }

        @Override public void waitUntilCompletion() throws InterruptedException {
            scheduler.waitUntilComplete();
            getLog().debug("Single " + getDatasourceName() + " workload is complete, completed receipt ID = " + getID());
        }
    }

    private class CompositingReceipt extends AbstractReceipt {
        private final boolean rethrowExceptions;
        private final List<Receipt> receipts;
        private final Map<Receipt, Exception> encounteredExceptions;
        private boolean finished = false;

        private CompositingReceipt(String combinedDatasourceName, LoadType combinedLoadType, Receipt... receipts) {
            this(combinedDatasourceName, combinedLoadType, true, receipts);
        }

        /**
         * Create a new compositing receipt that tracks the status of all receipts wrapped in this object and is marked
         * as complete once all child receipts are complete.  Use the flag 'rethrowExceptions' to indicate whether the
         * compositing receipt should throw any exceptions thrown by the child receipts: in effect, this will result in
         * the first exception encountered by a child receipt being rethrown by the {@link #waitUntilCompletion()}
         * method.  If you specify false for this parameter, then child exceptions will be swallowed: they can be
         * obtained using the method
         *
         * @param combinedDatasourceName the name to assign to this "combined" datasource
         * @param combinedLoadType       the load type of the "combined" tasks
         * @param rethrowExceptions      whether exceptions that are thrown by child tasks should be rethrow or captured
         *                               and tracked
         * @param receipts               the set of receipts to composite
         */
        private CompositingReceipt(String combinedDatasourceName,
                                   LoadType combinedLoadType,
                                   boolean rethrowExceptions,
                                   Receipt... receipts) {
            super(combinedDatasourceName, combinedLoadType);
            this.rethrowExceptions = rethrowExceptions;
            this.receipts = Collections.synchronizedList(new ArrayList<Receipt>());
            this.encounteredExceptions = new HashMap<>();
            for (Receipt receipt : receipts) {
                addNextReceipt(receipt);
            }
        }

        public void addNextReceipt(Receipt receipt) {
            if (finished) {
                throw new IllegalStateException("Unable to add another receipt - finish() has already been called");
            }

            getLog().debug("Adding receipt to composite: " + receipt + "\n" +
                                   "(Posted by thread " + Thread.currentThread().getName() + ")");
            synchronized (receipts) {
                getLog().debug("Thread " + Thread.currentThread().getName() + " acquired lock on receipts");
                this.receipts.add(receipt);
            }
            getLog().debug("Thread " + Thread.currentThread().getName() + " released lock on receipts");
        }

        /**
         * Call this method to indicate that all receipts have been added to the composite, and that client threads can
         * check for completion
         */
        public void finish() {
            getLog().debug("Thread " + Thread.currentThread().getName() + " is designating receipt " + getID() +
                                   " as complete");
            finished = true;
            synchronized (this) {
                notifyAll();
            }
        }

        /**
         * Returns any exceptions that have been encountered by child tasks in this method, indexed by the receipt of
         * the task that threw them
         *
         * @return exceptions encountered by a child task, indexed by receipt
         */
        public Map<Receipt, Exception> getEncounteredExceptions() {
            return Collections.unmodifiableMap(encounteredExceptions);
        }

        @Override public synchronized void waitUntilCompletion() throws InterruptedException {
            while (!finished) {
                synchronized (this) {
                    try {
                        wait();
                    }
                    catch (InterruptedException e) {
                        getLog().debug("Interrupted whilst waiting for scheduling to finish");
                    }
                }
            }

            getLog().debug("Collecting " + receipts.size() + " subtask receipts, " +
                                   "waiting until all have completed...");
            synchronized (receipts) {
                getLog().debug("Thread " + Thread.currentThread().getName() +
                                       " acquired lock on receipts to test for completion");
                for (Receipt receipt : receipts) {
                    try {
                        receipt.waitUntilCompletion();
                    }
                    catch (Exception e) {
                        encounteredExceptions.put(receipt, e);
                        if (rethrowExceptions) {
                            throw e;
                        }
                    }
                }
            }
            getLog().debug("Thread " + Thread.currentThread().getName() +
                                   " released lock on receipts, testing for completion finished");
            getLog().debug("...all subtask receipts have been marked complete, this receipt has completed");
        }
    }
}
