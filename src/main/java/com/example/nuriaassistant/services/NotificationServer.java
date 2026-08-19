package com.example.nuriaassistant.services;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

public class NotificationServer {
    private final int port = 8080;
    // TODO: Move to secure environment variable
    private final String secretKey = "nuria-assistant-secret-key";
    private final Consumer<String> onMessageReceived;

    public NotificationServer(Consumer<String> onMessageReceived) {
        this.onMessageReceived = onMessageReceived;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/notify", exchange -> {
            // Verify security
            String key = exchange.getRequestHeaders().getFirst("X-API-KEY");
            if (!secretKey.equals(key)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            // Read message body
            String message = new String(exchange.getRequestBody().readAllBytes());

            // Notify controller
            onMessageReceived.accept(message);

            // Respond OK
            String response = "Notification received";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.setExecutor(null);
        server.start();
        System.out.println("Notification server started on port " + port);
    }
}
