package ar.marlbo;

import static ar.marlbo.Utils.readInt;
import static ar.marlbo.Utils.require;
import static java.util.stream.Collectors.toSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.LogManager;
import java.util.stream.Stream;

import ar.marlbo.JobRunner.JobDefinition;

public record Config(String host, int port, int concurrency, List<JobDefinition> jobDefinitions) {

    public static Config loadConfig(String path) throws IOException {
        Path configPath = Path.of(path);
        try (var stream = Files.newInputStream(configPath)) {
            LogManager.getLogManager().readConfiguration(stream);
        }
        var props = new Properties();
        try (var stream = Files.newInputStream(configPath)) {
            props.load(stream);
        }
        var host = props.getProperty("mulo.api.host", "localhost").trim();
        require(!host.isBlank(), "Missing host setting");
        int port = readInt(props.getProperty("mulo.api.port", "8000"), "Invalid port number");
        require(port > 0 && port < 65536, "Invalid port number");
        int concurrency = readInt(props.getProperty("mulo.concurrency"), "Invalid concurrency value");
        require(concurrency > 0, "Invalid concurrency value");
        var jobs =
                readTokens(props.getProperty("mulo.jobs", ""))
                        .map(job -> {
                            var run = props.getProperty("mulo.job." + job + ".run", "").trim();
                            require(!run.isBlank(), "Job " + job + ": No program specified");
                            var exec = new File(run);
                            require(exec.exists(), "Job " + job + ": Program " + run + " not found");
                            require(exec.canExecute(), "Job " + job + ": Program " + run + " not executable");
                            var inputs = readTokens(props.getProperty("mulo.job." + job + ".inputs", ""));
                            var envs = readTokens(props.getProperty("mulo.job." + job + ".envs", ""));
                            var sudoUser = props.getProperty("mulo.job." + job + ".sudo.user");
                            return new JobRunner.JobDefinition(
                                    job,
                                    run,
                                    inputs.collect(toSet()),
                                    envs.collect(toSet()),
                                    Optional.ofNullable(sudoUser).filter(s -> !s.isBlank())
                            );
                        })
                        .toList();
        return new Config(host, port, concurrency, jobs);
    }

    private static Stream<String> readTokens(String input) {
        if (input == null || input.isBlank()) return Stream.empty();
        return Arrays.stream(input.split("[\\s,]+"))
                     .filter(s -> !s.isBlank())
                     .distinct();
    }
}
