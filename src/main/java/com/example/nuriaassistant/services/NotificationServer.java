package com.example.nuriaassistant.services;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

public class NotificationServer {
    private final int port = 8080;
    private final String secretKey = "nuria-assistant-secret-key";
    private final Consumer<String> onMessageReceived;
    private HttpServer server;

    public NotificationServer(Consumer<String> onMessageReceived) {
        this.onMessageReceived = onMessageReceived;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/notify", exchange -> {
                String key = exchange.getRequestHeaders().getFirst("X-API-KEY");
                if (!secretKey.equals(key)) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }

                String message = new String(exchange.getRequestBody().readAllBytes());
                onMessageReceived.accept(message);

                String response = "Notification received";
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });
            server.setExecutor(null);
            server.start();
            System.out.println("Notification server started on port " + port);

            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        } catch (IOException e) {
            System.err.println("Warning: Notification server failed to start on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("Notification server stopped.");
        }
    }
}
