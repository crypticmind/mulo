package ar.marlbo;

import static ar.marlbo.Utils.LOGGER;
import static java.util.logging.Level.INFO;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

public class Mulo {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: Mulo <config-file>");
            System.exit(1);
        }
        var config = Utils.loadConfig(args[0]);
        var server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        var runner = new JobRunner(config.jobDefinitions());
        server.createContext("/", new Api(runner));
        server.setExecutor(Executors.newWorkStealingPool(10));
        server.start();
        LOGGER.log(INFO, "Mulo listening on http://{0}:{1,number,#}", new Object[] {config.host(), config.port()});
    }
}
