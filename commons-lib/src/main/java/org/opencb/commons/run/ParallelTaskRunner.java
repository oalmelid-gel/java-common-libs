package org.opencb.commons.run;

import org.opencb.commons.io.DataReader;
import org.opencb.commons.io.DataWriter;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Created by hpccoll1 on 26/02/15.
 */
public class ParallelTaskRunner<I, O> {


    @FunctionalInterface
    static public interface Task<T, R> {
        default public void pre() {}
        List<R> apply(List<T> t);
        default public void post() {}
    }

    private final static Batch POISON_PILL = new Batch(Collections.emptyList(), -1);

    private final DataReader<I> reader;
    private final DataWriter<O> writer;
    private final List<Task<I, O>> tasks;
    private final Config config;

    private ExecutorService executorService;
    private BlockingQueue<Batch<I>> readBlockingQueue;
    private BlockingQueue<Batch<O>> writeBlockingQueue;

    private int numBatches = 0;
    private int finishedTasks = 0;
    private long timeBlockedAtPutRead = 0;
    private long timeBlockedAtTakeRead = 0;
    private long timeBlockedAtPutWrite = 0;
    private long timeBlockedAtTakeWrite = 0;
    private long timeReading = 0;
    private long timeTaskApply = 0;
    private long timeWriting;

	private List<Future> futureTasks;
    private List<Exception> exceptions;

//    protected static Logger logger = LoggerFactory.getLogger(SimpleThreadRunner.class);

    public static class Config {
        public Config(int numTasks, int batchSize, int capacity, boolean sorted) {
            this.numTasks = numTasks;
            this.batchSize = batchSize;
            this.capacity = capacity;
            this.sorted = sorted;
        }

        public Config(int numTasks, int batchSize, int capacity, boolean abortOnFail, boolean sorted) {
            this.numTasks = numTasks;
            this.batchSize = batchSize;
            this.capacity = capacity;
            this.abortOnFail = abortOnFail;
            this.sorted = sorted;
        }

        int numTasks;
        int batchSize;
        int capacity;
        boolean abortOnFail = true;
        boolean sorted;
//        int timeout;
    }

    private static class Batch<T> implements Comparable<Batch<T>> {
        final List<T> batch;
        final int position;

        private Batch(List<T> batch, int position) {
            this.batch = batch;
            this.position = position;
        }

        @Override
        public int compareTo(Batch<T> o) {
            return 0;
        }
    }

    /**
     *
     * @param reader        Unique DataReader. If null, empty batches will be generated
     * @param task          Task to be used. Will be used the same instance in all threads
     * @param writer        Unique DataWriter. If null, data generated by the task will be lost.
     * @param config
     * @throws Exception
     */
    public ParallelTaskRunner(DataReader<I> reader, Task<I, O> task, DataWriter<O> writer, Config config) throws Exception {
        this.config = config;
        this.reader = reader;
        this.writer = writer;
        this.tasks = new ArrayList<>(config.numTasks);
        for (int i = 0; i < config.numTasks; i++) {
            tasks.add(task);
        }

        check();
    }

    /**
     *
     * @param reader        Unique DataReader. If null, empty batches will be generated
     * @param taskSupplier  TaskGenerator. Will generate a new task for each thread
     * @param writer        Unique DataWriter. If null, data generated by the task will be lost.
     * @param config
     * @throws Exception
     */
    public ParallelTaskRunner(DataReader<I> reader, Supplier<Task<I, O>> taskSupplier, DataWriter<O> writer, Config config) throws Exception {
        this.config = config;
        this.reader = reader;
        this.writer = writer;
        this.tasks = new ArrayList<>(config.numTasks);
        for (int i = 0; i < config.numTasks; i++) {
            tasks.add(taskSupplier.get());
        }

        check();
    }

    /**
     *
     * @param reader        Unique DataReader. If null, empty batches will be generated
     * @param tasks         Generated Tasks. Each task will be used in one thread. Will use tasks.size() as "numTasks".
     * @param writer        Unique DataWriter. If null, data generated by the task will be lost.
     * @param config
     * @throws Exception
     */
    public ParallelTaskRunner(DataReader<I> reader, List<Task<I, O>> tasks, DataWriter<O> writer, Config config) throws Exception {
        this.config = config;
        this.reader = reader;
        this.writer = writer;
        this.tasks = tasks;

        check();
    }

    private void check() throws Exception {
        if (tasks == null || tasks.isEmpty()) {
            throw new Exception("Must provide at least one task");
        }
        if (tasks.size() != config.numTasks) {
            //WARN!!
        }
    }

    private void init() {
        finishedTasks = 0;
        if (reader != null) {
            readBlockingQueue = new ArrayBlockingQueue<>(config.capacity);
        }

        if (writer != null) {
            writeBlockingQueue = new ArrayBlockingQueue<>(config.capacity);
        }

        executorService = Executors.newFixedThreadPool(tasks.size() + (writer == null ? 0 : 1));
        futureTasks = new ArrayList<Future>(); // assume no parallel access to this list
        exceptions = Collections.synchronizedList(new LinkedList<>());
    }

    public void run() throws ExecutionException {
        init();

        if (reader != null) {
            reader.open();
            reader.pre();
        }

        if (writer != null) {
            writer.open();
            writer.pre();
        }

        for (Task<I, O> task : tasks) {
            task.pre();
        }

        for (Task<I, O> task : tasks) {
            doSubmit(new TaskRunnable(task));
        }
        if (writer != null) {
            doSubmit(new WriterRunnable(writer));
        }
        try{
	        if (reader != null) {
	            readLoop();  //Use the main thread for reading
	        }
	
	        executorService.shutdown();
	        try {
	            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); // TODO further action - this is not good!!!
	        } catch (InterruptedException e) {
	            e.printStackTrace();
	        }
        } catch (TimeoutException e) {
			e.printStackTrace();
		} finally{
        	if(!executorService.isShutdown()){
        		executorService.shutdownNow(); // shut down now if not done so (e.g. execption)
        	}
        }

        for (Task<I, O> task : tasks) {
            task.post();
        }

        if (reader != null) {
            reader.post();
            reader.close();
        }

        if (writer != null) {
            writer.post();
            writer.close();
        }


        if (reader != null) {
            System.err.println("read:  timeReading                  = " + timeReading / 1000000000.0 + "s");
            System.err.println("read:  timeBlockedAtPutRead         = " + timeBlockedAtPutRead / 1000000000.0 + "s");
            System.err.println("task;  timeBlockedAtTakeRead        = " + timeBlockedAtTakeRead / 1000000000.0 + "s");
        }

            System.err.println("task;  timeTaskApply                = " + timeTaskApply / 1000000000.0 + "s");

        if (writer != null) {
            System.err.println("task;  timeBlockedAtPutWrite        = " + timeBlockedAtPutWrite / 1000000000.0 + "s");
            System.err.println("write: timeBlockedWatingDataToWrite = " + timeBlockedAtTakeWrite / 1000000000.0 + "s");
            System.err.println("write: timeWriting                  = " + timeWriting / 1000000000.0 + "s");
        }

        if (config.abortOnFail && !exceptions.isEmpty()) {
            throw new ExecutionException("Error while running ParallelTaskRunner. Found " + exceptions.size() + " exceptions.", exceptions.get(0));
        }
    }

    public List<Exception> getExceptions() {
        return exceptions;
    }

    private void doSubmit(Runnable taskRunnable) {
		Future ftask = executorService.submit(taskRunnable);
		futureTasks.add(ftask);
	}

    private void readLoop() throws TimeoutException, ExecutionException {
        try {
	        long start;
	        Batch<I> batch;
	
	        batch = readBatch();
	
	        while (batch.batch != null && !batch.batch.isEmpty()) {
                //System.out.println("reader: prePut readBlockingQueue " + readBlockingQueue.size());
                start = System.nanoTime();
                int cntloop = 0;
                // continues lock of queue if jobs fail - check what's happening!!!
                while(!readBlockingQueue.offer(batch, 5, TimeUnit.SECONDS)){
                	if(!isJobsRunning()){
                		throw new IllegalStateException(String.format("No runners but queue with %s items!!!", readBlockingQueue.size()));
                	}
                	// check if something failed
                	if((cntloop++) > 10){
                		// something went wrong!!!
                		throw new TimeoutException(String.format("Queue got stuck with %s items!!!", readBlockingQueue.size()));
                	}

                }
                timeBlockedAtPutRead += System.nanoTime() - start;
                if (config.abortOnFail && !exceptions.isEmpty()) {
                    //Some error happen. Abort
                    System.err.println("Abort task thread on fail");
                    break;
                }
                //System.out.println("reader: preRead");
                batch = readBatch();
                //System.out.println("reader: batch.size = " + batch.size());
	        }
            //logger.debug("reader: POISON_PILL");
            readBlockingQueue.put(POISON_PILL);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private boolean isJobsRunning() throws InterruptedException, ExecutionException {
    	
    	List<Future> fList = new ArrayList<Future>(this.futureTasks);
		for (int i = 0; i < fList.size(); i++) {
			Future f = fList.get(i);
			if(f.isCancelled()){
				this.futureTasks.remove(f);
			} else if(f.isDone()){
				this.futureTasks.remove(f);
				f.get(); // check for exceptions
			}
		}
		return !this.futureTasks.isEmpty();
	}

	private Batch<I> readBatch() {
        long start;
        Batch<I> batch;
        start = System.nanoTime();
        int position = numBatches++;
        try {
            batch = new Batch<I>(reader.read(config.batchSize), position);
        } catch (Exception e) {
            System.err.println("Error reading batch " + position + "" + e.toString());
            e.printStackTrace();
            batch = POISON_PILL;
            exceptions.add(e);
        }
        timeReading += System.nanoTime() - start;
        return batch;
    }

    class TaskRunnable implements Runnable {

        final Task<I,O> task;

        long threadTimeBlockedAtTakeRead = 0;
        long threadTimeBlockedAtSendWrite = 0;
        long threadTimeTaskApply = 0;

        TaskRunnable(Task<I,O> task) {
            this.task = task;
        }
        @Override
        public void run() {
            try {
	            Batch<I> batch = POISON_PILL;
	
	            try {
	                batch = getBatch();
	            } catch (InterruptedException e) {
	                e.printStackTrace();
	            }
	            List<O> batchResult = null;
                /**
                 *  Exit situations:
                 *      batch == POISON_PILL    -> The reader thread finish reading. Send poison pill.
                 *      batchResult.isEmpty()   -> If there is no reader thread, and the last batch was empty.
                 *      !exceptions.isEmpty()   -> If there is any exception, abort. Requires Config.abortOnFail == true
                 */
                while (batch != POISON_PILL) {
                    long start;
                    //System.out.println("task: apply");
                    start = System.nanoTime();
                    try {
                        batchResult = task.apply(batch.batch);
                    } catch (Exception e) {
                        System.err.println("Error processing batch " + batch.position + "");
                        e.printStackTrace();
                        batchResult = null;
                        exceptions.add(e);
                    }
                    threadTimeTaskApply += System.nanoTime() - start;

                    if (readBlockingQueue == null && batchResult != null && batchResult.isEmpty()) {
                        //There is no readers and the last batch is empty
                        break;
                    }
                    if (config.abortOnFail && !exceptions.isEmpty()) {
                        //Some error happen. Abort
                        System.err.println("Abort task thread on fail");
                        break;
                    }

                    start = System.nanoTime();
                    if (writeBlockingQueue != null) {
                        writeBlockingQueue.put(new Batch<O>(batchResult, batch.position));
                    }
                    //System.out.println("task: apply done");
                    threadTimeBlockedAtSendWrite += System.nanoTime() - start;
                    batch = getBatch();
	            }
	            synchronized (tasks) {
	                timeBlockedAtPutWrite += threadTimeBlockedAtSendWrite;
	                timeTaskApply += threadTimeTaskApply;
	                timeBlockedAtTakeRead += threadTimeBlockedAtTakeRead;
	                finishedTasks++;
	                if (tasks.size() == finishedTasks) {
	                    if (writeBlockingQueue != null) {
                            writeBlockingQueue.put(POISON_PILL);
	                    }
	                }
	            }
            } catch (InterruptedException e) { // move to this position - stop any other calculations !!!
                e.printStackTrace(); 
                Thread.currentThread().interrupt(); // set to flag issue
            }
        }

        private Batch<I> getBatch() throws InterruptedException {
            Batch<I> batch;
            if (readBlockingQueue == null) {
                return new Batch<>(Collections.<I>emptyList(), numBatches++);
            } else {
                long start = System.nanoTime();
                batch = readBlockingQueue.take();
                threadTimeBlockedAtTakeRead += start - System.currentTimeMillis();
                //System.out.println("task: readBlockingQueue = " + readBlockingQueue.size() + " batch.size : " + batch.size() + " : " + batchSize);
                if (batch == POISON_PILL) {
                    //logger.debug("task: POISON_PILL");
                    readBlockingQueue.put(POISON_PILL);
                }
                return batch;
            }
        }
    }

    class WriterRunnable implements Runnable {

        final DataWriter<O> dataWriter;

        WriterRunnable(DataWriter<O> dataWriter) {
            this.dataWriter = dataWriter;
        }

        @Override
        public void run() {
            Batch<O> batch = POISON_PILL;
            try {
                batch = getBatch();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            long start;
            while (batch != POISON_PILL) {
                try {
                    start = System.nanoTime();
//                    System.out.println("writer: write");
                    try {
                        dataWriter.write(batch.batch);
                    } catch (Exception e) {
                        System.err.println("Error writing batch " + batch.position + "");
                        e.printStackTrace();
                        exceptions.add(e);
                    }

                    if (config.abortOnFail && !exceptions.isEmpty()) {
                        //Some error happen. Abort
                        System.err.println("Abort writing thread on fail");
                        break;
                    }

//                    System.out.println("writer: wrote");
                    timeWriting += System.nanoTime() - start;
                    batch = getBatch();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        private Batch<O> getBatch() throws InterruptedException {
//                System.out.println("writer: writeBlockingQueue = " + writeBlockingQueue.size());
            long start = System.nanoTime();
            Batch<O> batch = writeBlockingQueue.take();
            timeBlockedAtTakeWrite += System.nanoTime() - start;
            if (batch == POISON_PILL) {
//                logger.debug("writer: POISON_PILL");
                writeBlockingQueue.put(POISON_PILL);
            }
            return batch;
        }
    }

}
