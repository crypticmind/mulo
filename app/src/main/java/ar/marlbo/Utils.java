package ar.marlbo;

import static java.util.stream.Collectors.toSet;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class Utils {

    public static final Logger LOGGER = Logger.getLogger("mulo");

    public static Map<String, String> parseFormUrlEncoded(String encodedString, String encoding)
            throws UnsupportedEncodingException {
        var map = new HashMap<String, String>();
        if (encodedString == null || encodedString.isEmpty()) {
            return map;
        }
        var pairs = encodedString.split("&");
        for (var pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx != -1) {
                var key = pair.substring(0, idx);
                var value = pair.substring(idx + 1);
                var decodedKey = URLDecoder.decode(key, encoding);
                var decodedValue = URLDecoder.decode(value, encoding);
                map.put(decodedKey, decodedValue);
            } else {
                var decodedKey = URLDecoder.decode(pair, encoding);
                map.put(decodedKey, "");
            }
        }
        return map;
    }

    public static String generateId() {
        return UUID.randomUUID().toString();
    }

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
        int port;
        try {
            port = Integer.parseInt(props.getProperty("mulo.api.port", "8080").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port number: " + e.getMessage());
        }
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
        return new Config(host, port, jobs);
    }

    private static Stream<String> readTokens(String input) {
        if (input == null || input.isBlank()) return Stream.empty();
        return Arrays.stream(input.split("[\\s,]+"))
                     .filter(s -> !s.isBlank())
                     .distinct();
    }

    public static <T> void require(boolean cond, String message) {
        if (!cond) {
            throw new IllegalArgumentException(message);
        }
    }
}
