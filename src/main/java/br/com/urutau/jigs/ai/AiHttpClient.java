package br.com.urutau.jigs.ai;

import java.net.http.HttpClient;
import java.time.Duration;

final class AiHttpClient {
    private static HttpClient client;

    private AiHttpClient() {}

    // Open the outbound transport only when a provider is actually called.
    static synchronized HttpClient get() {
        if (client == null) {
            client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        }
        return client;
    }
}
