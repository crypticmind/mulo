package ar.marlbo;

import static ar.marlbo.Utils.LOGGER;
import static ar.marlbo.Utils.require;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toMap;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

public class JobRunner {

    public record JobDefinition(
            String name,
            String path,
            Set<String> inputs,
            Set<String> envs,
            Optional<String> runAs
    ) {
    }

    sealed public interface SubmittedJob permits QueuedJob, RunJob, FinishedJob, FailedJob {
    }

    sealed public interface HasOutput permits RunJob, FinishedJob {
        File output();
    }

    public record QueuedJob(
            String id,
            Map<String, String> environment
    ) implements SubmittedJob {
    }

    public record RunJob(
            String id,
            Map<String, String> environment,
            Process process,
            File output
    ) implements SubmittedJob, HasOutput {
    }

    public record FinishedJob(
            String id,
            Map<String, String> environment,
            Process process,
            File output
    ) implements SubmittedJob, HasOutput {
    }

    public record FailedJob(
            String id,
            Map<String, String> environment,
            Exception exception
    ) implements SubmittedJob {
    }

    @FunctionalInterface
    public interface ProcessFactory {
        Process apply(JobDefinition job, Map<String, String> environment, File output) throws IOException;
    }

    private final Map<String, JobDefinition> definitions;

    private final Map<String, SubmittedJob> jobs = new ConcurrentHashMap<>();

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Semaphore semaphore;

    private final ProcessFactory processFactory;

    public JobRunner(int concurrency, List<JobDefinition> definitions) {
        this(concurrency, definitions, DEFAULT_PROCESS_FACTORY);
    }

    public JobRunner(int concurrency, List<JobDefinition> definitions, ProcessFactory processFactory) {
        require(concurrency > 0, "concurrency must be > 0");
        require(definitions != null && !definitions.isEmpty(), "no jobs defined");
        require(processFactory != null, "processFactory is null");
        this.semaphore = new Semaphore(concurrency);
        this.definitions = definitions.stream().collect(toMap(JobDefinition::name, Function.identity()));
        this.processFactory = processFactory;
        LOGGER.log(
                INFO,
                "Mulo has {0} jobs defined, and can run up to {1} concurrently",
                new Object[]{definitions.size(), concurrency}
        );
    }

    public String run(String name, Map<String, String> params) {
        var job = definitions.get(name);
        require(job != null, "Job not found: " + name);
        var jobId = Utils.generateId();
        var filteredParams =
                params.entrySet()
                      .stream()
                      .filter(e -> job.inputs.contains(e.getKey()))
                      .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        var filteredEnvs =
                System.getenv().entrySet()
                      .stream()
                      .filter(e -> job.envs.contains(e.getKey()))
                      .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        var environment = new HashMap<String, String>();
        environment.putAll(filteredParams);
        environment.putAll(filteredEnvs);
        jobs.put(jobId, new QueuedJob(jobId, environment));
        LOGGER.log(INFO, "Job {0} submitted", jobId);
        executor.execute(() -> {
            try {
                semaphore.acquire();
                var output = File.createTempFile("mulo." + jobId + ".", ".job");
                var process = processFactory.apply(job, environment, output);
                jobs.compute(jobId, (k, v) -> new RunJob(jobId, environment, process, output));
                LOGGER.log(INFO, "Job {0} started", jobId);
                LOGGER.log(INFO, "Job {0} output file: {1}", new Object[]{jobId, output.getAbsolutePath()});
                var code = process.waitFor();
                jobs.compute(jobId, (k, v) -> new FinishedJob(jobId, environment, process, output));
                LOGGER.log(INFO, "Job {0} exit status: {1}", new Object[]{jobId, code});
            } catch (Exception ex) {
                jobs.compute(jobId, (k, v) -> new FailedJob(jobId, environment, ex));
                LOGGER.log(SEVERE, "Job {0} execution failed: {1}", new Object[]{jobId, ex});
            } finally {
                semaphore.release();
            }
        });
        return jobId;
    }

    public Optional<SubmittedJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private static final ProcessFactory DEFAULT_PROCESS_FACTORY = (job, environment, output) -> {
        var builder =
                job.runAs.map(u -> new ProcessBuilder("sudo", "-u", u, job.path))
                         .orElse(new ProcessBuilder(job.path));
        builder.redirectErrorStream(true);
        builder.redirectOutput(output);
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder.start();
    };
}
