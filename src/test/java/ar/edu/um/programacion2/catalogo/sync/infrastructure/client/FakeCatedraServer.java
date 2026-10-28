package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Cátedra simulada con el servidor HTTP del JDK ({@code com.sun.net.httpserver}): abre un socket real en
 * {@code 127.0.0.1} y un puerto libre, así se pueden probar timeouts y el servidor apagado, que un mock sin red no
 * cubre. Registra cada pedido recibido y responde lo que se le configure.
 */
final class FakeCatedraServer implements AutoCloseable {

    /** Pedido tal como llegó al servidor. */
    record ReceivedRequest(String method, String path, String authorization) {}

    private interface Behavior {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    /** Tope para los handlers colgados: si un test no cierra el servidor, igual terminan. */
    private static final long MAX_HANG_SECONDS = 10;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch released = new CountDownLatch(1);
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Behavior behavior = exchange -> send(exchange, 200, "application/json", "{}");

    private FakeCatedraServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static FakeCatedraServer start() throws IOException {
        return new FakeCatedraServer();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<ReceivedRequest> requests() {
        return List.copyOf(requests);
    }

    void respondJson(int status, String body) {
        behavior = exchange -> send(exchange, status, "application/json", body);
    }

    void respondProblem(int status, String code) {
        String body = "{\"title\":\"Error\",\"status\":" + status + ",\"detail\":\"detalle libre\",\"code\":\"" + code + "\"}";
        behavior = exchange -> send(exchange, status, "application/problem+json", body);
    }

    void respondWithoutBody(int status) {
        behavior = exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        };
    }

    void redirectTo(String location) {
        behavior = exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        };
    }

    /** No responde nada hasta que se cierre el servidor: la cátedra está lenta. */
    void hangBeforeResponding() {
        behavior = exchange -> released.await(MAX_HANG_SECONDS, TimeUnit.SECONDS);
    }

    /** Manda las cabeceras y parte del cuerpo, y después se cuelga: el snapshot llega a medias. */
    void hangInTheMiddleOfTheBody(String body) {
        behavior = exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes, 0, bytes.length / 2);
            out.flush();
            released.await(MAX_HANG_SECONDS, TimeUnit.SECONDS);
        };
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(
            new ReceivedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization")
            )
        );
        try {
            behavior.handle(exchange);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
