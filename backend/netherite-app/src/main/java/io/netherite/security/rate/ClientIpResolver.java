package io.netherite.security.rate;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP used to key per-source rate-limit buckets.
 *
 * <p>The application is deployed behind a trusted reverse proxy (Caddy) and the container has
 * {@code server.forward-headers-strategy: native}; the Tomcat RemoteIpValve therefore rewrites
 * {@code getRemoteAddr()} from {@code X-Forwarded-For} <em>only when the immediate peer is a
 * trusted internal proxy</em>. This filter deliberately never parses client headers itself — a
 * client that sends a forged {@code X-Forwarded-For} merely appends an entry to the far end of a
 * list that the valve walks right-to-left, so the resolved address remains the real source. With no
 * proxy present the value is simply the TCP peer address.</p>
 */
@Component
public class ClientIpResolver {

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
    }
}