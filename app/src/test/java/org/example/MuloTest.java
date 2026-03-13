package org.example;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ar.marlbo.Api;
import ar.marlbo.JobRunner;
import ar.marlbo.JobRunner.JobDefinition;
import ar.marlbo.JobRunner.ProcessFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MuloTest {

    static int port;

    static String endpoint;

    private static class TestProcess extends Process {

        @Override
        public OutputStream getOutputStream() {
            return null;
        }

        @Override
        public InputStream getInputStream() {
            return null;
        }

        @Override
        public InputStream getErrorStream() {
            return null;
        }

        @Override
        public int waitFor() throws InterruptedException {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }

    private static final Object signal = new Object();

    private static final Map<String, String> environment = new HashMap<>();

    private static final JobDefinition waitForSignal =
            new JobDefinition("wait-for-signal", "?", Set.of(), Set.of(), Optional.empty());

    private static final JobDefinition fail =
            new JobDefinition("fail", "?", Set.of(), Set.of(), Optional.empty());

    private static final JobDefinition nonZeroStatus =
            new JobDefinition("non-zero-status", "?", Set.of(), Set.of(), Optional.empty());

    private static final JobDefinition shareEnvironment =
            new JobDefinition("share-environment", "?", Set.of("p1", "p2"), Set.of("e1", "e2"), Optional.empty());

    private static final JobDefinition writeOutput =
            new JobDefinition("write-output", "?", Set.of(), Set.of(), Optional.empty());

    private static final ProcessFactory TEST_PROCESS_FACTORY = (job, environment, output) -> switch (job.name()) {
        case "wait-for-signal" -> new TestProcess() {
            @Override
            public int waitFor() throws InterruptedException {
                synchronized (signal) {
                    signal.wait();
                }
                return super.waitFor();
            }
        };
        case "fail" -> throw new RuntimeException("Execution failed");
        case "non-zero-status" -> new TestProcess() {
            @Override
            public int waitFor() {
                return 123;
            }

            @Override
            public int exitValue() {
                return 123;
            }
        };
        case "share-environment" -> new TestProcess() {
            @Override
            public int waitFor() throws InterruptedException {
                MuloTest.environment.putAll(environment);
                return super.waitFor();
            }
        };
        case "write-output" -> new TestProcess() {
            @Override
            public int waitFor() throws InterruptedException {
                try (var writer = new FileWriter(output)) {
                    writer.write("test-output");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return super.waitFor();
            }
        };
        default -> throw new IllegalStateException("Unexpected value: " + job.name());
    };

    private static final HttpClient client = HttpClient.newHttpClient();

    private static final int CONCURRENCY = 2;

    private record Status(String status, Optional<String> info) {
    }

    private static final Pattern STATUS_PATTERN = Pattern.compile("(\\w+)\\s*(.+)?");

    @BeforeAll
    static void setupAll() throws IOException {
        try (var serverSocket = new ServerSocket(0)) {
            port = serverSocket.getLocalPort();
        }
        endpoint = "http://localhost:" + port;
        var server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        var definitions = List.of(waitForSignal, fail, nonZeroStatus, shareEnvironment, writeOutput);
        var runner = new JobRunner(CONCURRENCY, definitions, TEST_PROCESS_FACTORY);
        server.createContext("/", new Api(runner));
        server.setExecutor(Executors.newWorkStealingPool(10));
        server.start();
    }

    @BeforeEach
    void setupEach() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    @AfterEach
    void clearUpEach() {
        synchronized (signal) {
            signal.notifyAll();
        }
        environment.clear();
    }

    @Test
    void testSubmit_unknownJob() throws Exception {
        var response = submit("unknown", Map.of());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void testSubmit_success() throws Exception {
        var response = submit("wait-for-signal", Map.of());
        assertThat(response.statusCode()).isEqualTo(202);
        var jobId = response.body();
        assertThat(jobId).isNotBlank();
    }

    @Test
    void testStatus_notFound() throws Exception {
        var response = status("unknown");
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void testStatus_queued() throws Exception {
        for (int i = 0; i < CONCURRENCY; i++) {
            submit("wait-for-signal", Map.of());
        }
        var response = submit("wait-for-signal", Map.of());
        assertThat(response.statusCode()).isEqualTo(202);
        var jobId = response.body();
        response = status(jobId);
        assertThat(response.statusCode()).isEqualTo(200);
        var status = parseStatus(response.body());
        assertThat(status.status).isEqualTo("queued");
        assertThat(status.info).isEmpty();
    }

    @Test
    void testStatus_runningAndStopped() throws Exception {
        var response = submit("wait-for-signal", Map.of());
        assertThat(response.statusCode()).isEqualTo(202);
        var jobId = response.body();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var statusResp = status(jobId);
            assertThat(statusResp.statusCode()).isEqualTo(200);
            var status = parseStatus(statusResp.body());
            assertThat(status.status).isEqualTo("running");
            assertThat(status.info).isEmpty();
        });
        synchronized (signal) {
            signal.notifyAll();
        }
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var statusResp = status(jobId);
            assertThat(statusResp.statusCode()).isEqualTo(200);
            var status = parseStatus(statusResp.body());
            assertThat(status.status).isEqualTo("stopped");
            assertThat(status.info).contains("0");
        });
    }

    @Test
    void testStatus_failed() throws Exception {
        var response = submit("fail", Map.of());
        assertThat(response.statusCode()).isEqualTo(202);
        var jobId = response.body();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var statusResp = status(jobId);
            assertThat(statusResp.statusCode()).isEqualTo(200);
            var status = parseStatus(statusResp.body());
            assertThat(status.status).isEqualTo("failed");
            assertThat(status.info).contains("java.lang.RuntimeException: Execution failed");
        });
    }

    @Test
    void testStatus_nonZeroStatus() throws Exception {
        var response = submit("non-zero-status", Map.of());
        assertThat(response.statusCode()).isEqualTo(202);
        var jobId = response.body();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var statusResp = status(jobId);
            assertThat(statusResp.statusCode()).isEqualTo(200);
            var status = parseStatus(statusResp.body());
            assertThat(status.status).isEqualTo("stopped");
            assertThat(status.info).contains("123");
        });
    }

    @Test
    void testEnvironment() throws Exception {
        var response = submit("share-environment", Map.of("p1", "p1v", "p2", "p2v", "p3", "irrelevant"));
        assertThat(response.statusCode()).isEqualTo(202);
        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertThat(environment).hasFieldOrPropertyWithValue("p1", "p1v");
            assertThat(environment).hasFieldOrPropertyWithValue("p2", "p2v");
            assertThat(environment).doesNotContainKey("p3");
            assertThat(environment).hasFieldOrPropertyWithValue("e1", "e1v");
            assertThat(environment).hasFieldOrPropertyWithValue("e2", "e2v");
            assertThat(environment).doesNotContainKey("e3");
        });
    }

    @Test
    void testOutput_queued() throws Exception {
        for (int i = 0; i < CONCURRENCY; i++) {
            submit("wait-for-signal", Map.of());
        }
        var response = submit("wait-for-signal", Map.of());
        assertThat(response.statusCode()).isEqualTo(202);
        var jobId = response.body();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var statusResp = status(jobId);
            assertThat(statusResp.statusCode()).isEqualTo(200);
            var status = parseStatus(statusResp.body());
            assertThat(status.status).isEqualTo("queued");
        });
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var outputResp = output(jobId, null);
            assertThat(outputResp.statusCode()).isEqualTo(204);
            assertThat(outputResp.headers().firstValue("Content-Range")).isEmpty();
            assertThat(outputResp.body()).isEmpty();
        });
    }

    @Test
    void testOutput_normalOutput() throws Exception {
        var response = submit("write-output", Map.of());
        assertThat(response.statusCode()).isEqualTo(202);
        var jobId = response.body();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var statusResp = status(jobId);
            assertThat(statusResp.statusCode()).isEqualTo(200);
            var status = parseStatus(statusResp.body());
            assertThat(status.status).isEqualTo("stopped");
        });
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var outputResp = output(jobId, null);
            assertThat(outputResp.statusCode()).isEqualTo(216);
            assertThat(outputResp.headers().firstValue("Content-Range")).contains("bytes 0/*");
            assertThat(outputResp.body()).isEqualTo("test-output");
        });
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var outputResp = output(jobId, 5);
            assertThat(outputResp.statusCode()).isEqualTo(216);
            assertThat(outputResp.headers().firstValue("Content-Range")).contains("bytes 5/*");
            assertThat(outputResp.body()).isEqualTo("output");
        });
    }

    @Test
    void testOutput_failure() throws Exception {
        var response = submit("fail", Map.of());
        assertThat(response.statusCode()).isEqualTo(202);
        var jobId = response.body();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var statusResp = status(jobId);
            assertThat(statusResp.statusCode()).isEqualTo(200);
            var status = parseStatus(statusResp.body());
            assertThat(status.status).isEqualTo("failed");
        });
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var outputResp = output(jobId, null);
            assertThat(outputResp.statusCode()).isEqualTo(200);
            assertThat(outputResp.headers().firstValue("Content-Range")).isEmpty();
            assertThat(outputResp.body()).isEqualTo("java.lang.RuntimeException: Execution failed");
        });
    }

    @Test
    void testHealth() throws Exception {
        var endpoint = URI.create(MuloTest.endpoint);
        var request = HttpRequest.newBuilder().uri(endpoint).build();
        var response = client.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Mulo ready!");
    }

    private HttpResponse<String> submit(String name, Map<String, String> params) throws Exception {
        var endpoint = URI.create(MuloTest.endpoint + "/submit/" + name);
        var formBody =
                params.entrySet().stream()
                      .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), UTF_8))
                      .collect(Collectors.joining("&"));
        var request =
                HttpRequest.newBuilder().uri(endpoint)
                           .header("Content-Type", "application/x-www-form-urlencoded")
                           .POST(BodyPublishers.ofString(formBody))
                           .build();
        return client.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> status(String jobId) throws Exception {
        var endpoint = URI.create(MuloTest.endpoint + "/status/" + jobId);
        var request = HttpRequest.newBuilder().uri(endpoint).build();
        return client.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> output(String jobId, Integer from) throws Exception {
        var endpoint = URI.create(MuloTest.endpoint + "/output/" + jobId);
        var request =
                from != null
                        ? HttpRequest.newBuilder().uri(endpoint).header("Range", "bytes=" + from + "-").build()
                        : HttpRequest.newBuilder().uri(endpoint).build();
        return client.send(request, BodyHandlers.ofString());
    }

    private Status parseStatus(String statusLine) {
        var matcher = STATUS_PATTERN.matcher(statusLine);
        assertThat(matcher).matches();
        var status = matcher.group(1);
        assertThat(status).isIn("queued", "running", "stopped", "failed");
        return new Status(status, Optional.ofNullable(matcher.group(2)).filter(s -> !s.isBlank()));
    }
}
