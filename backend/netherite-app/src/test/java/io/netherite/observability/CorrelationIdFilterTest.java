package io.netherite.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WS8 — request correlation boundary.
 *
 * <p>Covers {@link CorrelationIdFilter}: a request identifier is guaranteed in the MDC
 * while the chain runs, echoed on every response via {@code X-Correlation-Id}, and cleared
 * afterwards — including on exceptional paths. Client-supplied identifiers are accepted
 * only when conservative and bounded; anything else is replaced by a fresh UUID, so a
 * caller can never inject log-injection payloads or unbounded strings.</p>
 */
class CorrelationIdFilterTest {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesRequestIdWhenHeaderAbsentAndEchoesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                seenDuringChain.set(MDC.get(CorrelationIdFilter.MDC_REQUEST_ID)));

        assertThat(seenDuringChain.get()).isNotNull().matches(UUID_PATTERN.asPredicate());
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(seenDuringChain.get());
        assertThat(request.getAttribute(CorrelationIdFilter.ATTR_REQUEST_ID)).isEqualTo(seenDuringChain.get());
        assertThat(MDC.get(CorrelationIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_EVENT)).isNull();
    }

    @Test
    void preservesConservativeClientSuppliedIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/test");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "client-trace_2024.01-2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("client-trace_2024.01-2");
    }

    @Test
    void replacesHeaderWithUnsafeCharacters() throws Exception {
        String injected = "legit\u001b[31m.bad id!!";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/test");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, injected);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isNotEqualTo(injected)
                .matches(UUID_PATTERN.asPredicate());
    }

    @Test
    void replacesOversizedHeaderValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/test");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "a".repeat(129));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).hasSize(36);
    }

    @Test
    void clearsMdcWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = (req, res) -> {
            throw new IOException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(IOException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_EVENT)).isNull();
    }
}