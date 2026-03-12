package ar.marlbo;

import static ar.marlbo.Utils.LOGGER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Optional;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

class Api implements HttpHandler {

    private static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=(\\d+)-$");
    private static final String FROM_ZERO = "bytes=0-";

    private final JobRunner jobRunner;

    public Api(JobRunner jobRunner) {
        this.jobRunner = jobRunner;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        LOGGER.log(
                FINE,
                "Handling %s %s",
                new Object[]{exchange.getRequestMethod(), exchange.getRequestURI().getPath()}
        );
        var path = exchange.getRequestURI().getPath().split("/");
        var method = exchange.getRequestMethod();
        try {
            if (method.equals("GET") && path.length == 0) {
                health(exchange);
            } else if (method.equals("POST") && path[1].equals("submit") && path.length == 3) {
                submit(exchange, path[2]);
            } else if (method.equals("GET") && path[1].equals("status") && path.length == 3) {
                status(exchange, path[2]);
            } else if (method.equals("GET") && path[1].equals("output") && path.length == 3) {
                output(exchange, path[2]);
            } else {
                reply(exchange, 404, "Not Found");
            }
        } catch (Exception ex) {
            LOGGER.log(
                    SEVERE,
                    "Error on %s %s",
                    new Object[]{exchange.getRequestMethod(), exchange.getRequestURI().getPath(), ex}
            );
            reply(
                    exchange,
                    switch (ex) {
                        case IllegalArgumentException ignored -> 400;
                        case UnsupportedOperationException ignored -> 416;
                        default -> 500;
                    },
                    ex.getMessage() == null ? ex.toString() : ex.getMessage()
            );
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        reply(exchange, 200, "Mulo ready!");
    }

    private void submit(HttpExchange exchange, String name) throws IOException {
        try (var is = exchange.getRequestBody()) {
            var body = new String(is.readAllBytes(), UTF_8);
            var params = Utils.parseFormUrlEncoded(body, UTF_8.name());
            var jobId = jobRunner.run(name, params);
            reply(exchange, 202, jobId);
        }
    }

    private void status(HttpExchange exchange, String jobId) throws IOException {
        var maybeJob = jobRunner.getJob(jobId);
        if (maybeJob.isPresent()) {
            var process = maybeJob.get().process();
            var status = process.isAlive() ? "running" : "stopped " + process.exitValue();
            reply(exchange, 200, status);
        } else {
            reply(exchange, 404, "Not Found");
        }
    }

    private void output(HttpExchange exchange, String jobId) throws IOException {
        var maybeJob = jobRunner.getJob(jobId);
        if (maybeJob.isEmpty()) {
            reply(exchange, 404, "Not Found");
            return;
        }
        var job = maybeJob.get();
        var range = Optional.ofNullable(exchange.getRequestHeaders().getFirst("Range")).orElse(FROM_ZERO);
        var matcher = RANGE_PATTERN.matcher(range);
        if (!matcher.matches()) throw new UnsupportedRangeException("Invalid range");
        var from = Long.parseLong(matcher.group(1));
        try (var output = new RandomAccessFile(job.output(), "r"); var os = exchange.getResponseBody()) {
            output.seek(from);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "/*");
            exchange.sendResponseHeaders(216, 0);
            var buffer = new byte[1024];
            int read;
            while ((read = output.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }
    }

    private void reply(HttpExchange exchange, int status, String response) throws IOException {
        var bytes = response.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
