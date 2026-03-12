package ar.marlbo;

import static ar.marlbo.Utils.LOGGER;
import static java.util.logging.Level.INFO;
import static java.util.stream.Collectors.toMap;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    public record JobRun(String id, Process process, File output) {
    }

    private final Map<String, JobDefinition> definitions;

    private final Map<String, JobRun> jobs = new ConcurrentHashMap<>();

    public JobRunner(List<JobDefinition> definitions) {
        this.definitions = definitions.stream().collect(toMap(JobDefinition::name, Function.identity()));
    }

    public String run(String name, Map<String, String> params) throws IOException {
        var job = definitions.get(name);
        if (job == null) throw new IllegalArgumentException("Job not found: " + name);
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
        var jobId = Utils.generateId();
        var output = File.createTempFile("mulo." + jobId + ".", ".job");
        var builder =
                job.runAs.map(u -> new ProcessBuilder("sudo", "-u", u, job.path))
                         .orElse(new ProcessBuilder(job.path));
        builder.redirectErrorStream(true);
        builder.redirectOutput(output);
        builder.environment().clear();
        builder.environment().putAll(filteredEnvs);
        builder.environment().putAll(filteredParams);
        var process = builder.start();
        process.onExit()
               .thenAccept(p -> LOGGER.log(INFO, "Job {0} exit status: {1}", new Object[]{jobId, p.exitValue()}));
        jobs.put(jobId, new JobRun(jobId, process, output));
        LOGGER.log(INFO, "Job {0} started", jobId);
        LOGGER.log(INFO, "Job {0} output file: {1}", new Object[]{jobId, output.getAbsolutePath()});
        return jobId;
    }

    public Optional<JobRun> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
