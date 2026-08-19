package com.example.nuriaassistant.services;

import com.example.nuriaassistant.models.WeatherData;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

public class WeatherService {
    private final HttpClient httpClient;
    private final String apiKey;
    private final String city;

    public WeatherService(String apiKey, String city) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.apiKey = apiKey;
        this.city = city;
    }

    public void fetchWeather(Consumer<WeatherData> onSuccess, Consumer<Throwable> onError) {
        String url = String.format("https://api.openweathermap.org/data/2.5/weather?q=%s&units=metric&appid=%s", city, apiKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(json -> {
                    WeatherData data = parseJson(json);
                    Platform.runLater(() -> onSuccess.accept(data));
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> onError.accept(ex));
                    return null;
                });
    }

    private WeatherData parseJson(String json) {
        try {
            // Find "temp": value
            double temp = Double.parseDouble(json.split("\"temp\":")[1].split(",")[0]);
            // Find "main":"Value"
            String main = json.split("\"main\":\"")[1].split("\"")[0];
            return new WeatherData(temp, main);
        } catch (Exception e) {
            return new WeatherData(0.0, "Unknown");
        }
    }
}
