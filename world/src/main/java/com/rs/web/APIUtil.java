package com.rs.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.darkan.core.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

/**
 * Stub for legacy API utility class.
 */
public class APIUtil {

    private static final Gson GSON = new GsonBuilder().create();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static <T> void get(Class<T> type, String url, String apiKey, Consumer<T> cb) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", apiKey)
                .GET()
                .build();
            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    try {
                        cb.accept(GSON.fromJson(response.body(), type));
                    } catch (Exception e) {
                        cb.accept(null);
                    }
                })
                .exceptionally(e -> { cb.accept(null); return null; });
        } catch (Exception e) {
            cb.accept(null);
        }
    }

    public static <T> T getSync(Class<T> type, String url, String apiKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", apiKey)
                .GET()
                .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return GSON.fromJson(response.body(), type);
        } catch (Exception e) {
            return null;
        }
    }

    public static <T> void post(Class<T> type, Object body, String url, String apiKey, Consumer<T> cb) {
        try {
            String json = GSON.toJson(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    try {
                        if (type == null) {
                            if (cb != null) cb.accept(null);
                            return;
                        }
                        cb.accept(GSON.fromJson(response.body(), type));
                    } catch (Exception e) {
                        if (cb != null) cb.accept(null);
                    }
                })
                .exceptionally(e -> { if (cb != null) cb.accept(null); return null; });
        } catch (Exception e) {
            if (cb != null) cb.accept(null);
        }
    }

    public static <T> T postSync(Class<T> type, Object body, String url, String apiKey) {
        try {
            String json = GSON.toJson(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return GSON.fromJson(response.body(), type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Execute an OkHttp request and parse the response. Stub for legacy webhook code.
     */
    public static <T> void request(Class<T> type, okhttp3.Request request, Consumer<T> cb) {
        try {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    if (cb != null) cb.accept(null);
                }

                @Override
                public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                    try {
                        String body = response.body() != null ? response.body().string() : null;
                        if (type == null || body == null) {
                            if (cb != null) cb.accept(null);
                            return;
                        }
                        if (cb != null) cb.accept(GSON.fromJson(body, type));
                    } catch (Exception e) {
                        if (cb != null) cb.accept(null);
                    }
                }
            });
        } catch (Exception e) {
            if (cb != null) cb.accept(null);
        }
    }

    public static boolean authenticate(io.undertow.server.HttpServerExchange ex, String apiKey) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        return apiKey != null && apiKey.equals(auth);
    }

    public static void sendResponse(io.undertow.server.HttpServerExchange ex, int statusCode, Object data) {
        ex.setStatusCode(statusCode);
        ex.getResponseSender().send(GSON.toJson(data));
    }

    public static <T> void readJSON(io.undertow.server.HttpServerExchange ex, Class<T> type, Consumer<T> cb) {
        ex.getRequestReceiver().receiveFullString((exchange, body) -> {
            try {
                cb.accept(GSON.fromJson(body, type));
            } catch (Exception e) {
                cb.accept(null);
            }
        });
    }
}
