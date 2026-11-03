package io.netherite.support.http;

import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

public class NoRedirectJdkClientHttpRequestFactory extends JdkClientHttpRequestFactory {

    public NoRedirectJdkClientHttpRequestFactory() {
        this(Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    public NoRedirectJdkClientHttpRequestFactory(Duration connectTimeout, Duration readTimeout) {
        super(HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        setReadTimeout(readTimeout);
    }
}
