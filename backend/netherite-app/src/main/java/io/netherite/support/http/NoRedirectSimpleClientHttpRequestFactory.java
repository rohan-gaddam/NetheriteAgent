package io.netherite.support.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * {@link SimpleClientHttpRequestFactory} that never follows HTTP redirects.
 *
 * <p>Redirects are a classic SSRF/egress bypass: a permitted host redirecting to a
 * private/loopback/metadata destination would otherwise escape the validation performed
 * before the request. This factory forces {@code setInstanceFollowRedirects(false)} on
 * every connection so the connected host is always the SSRF-validated one.</p>
 */
public class NoRedirectSimpleClientHttpRequestFactory extends SimpleClientHttpRequestFactory {

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
    }
}