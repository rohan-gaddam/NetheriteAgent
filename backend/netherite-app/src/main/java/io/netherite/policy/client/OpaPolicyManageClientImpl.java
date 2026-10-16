package io.netherite.policy.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netherite.connectors.spi.SsrfGuardValidator;
import io.netherite.policy.model.OpaPolicyManageResult;
import io.netherite.policy.spi.OpaPolicyManageClient;
import io.netherite.support.http.NoRedirectSimpleClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

/**
 * HTTP client for the OPA management-plane API. Every mutation carries the
 * administrative bearer credential ({@code netherite.opa.token}) — an engine
 * that enforces its {@code system.authz} policy rejects everything else with
 * 401, so unauthorized callers cannot read or alter policy state.
 *
 * Fail-closed: SSRF rejection, transport errors and engine rejects all surface
 * as non-successful {@link OpaPolicyManageResult}s.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "netherite.opa.policy-path")
public class OpaPolicyManageClientImpl implements OpaPolicyManageClient {

    private final RestTemplate restTemplate;
    private final String opaUrl;
    private final SsrfGuardValidator ssrfGuardValidator;
    private final ObjectMapper objectMapper;
    private final String opaToken;

    @Autowired
    public OpaPolicyManageClientImpl(
            @Value("${netherite.opa.url}") String opaUrl,
            @Value("${netherite.opa.timeout-ms:200}") long timeoutMs,
            SsrfGuardValidator ssrfGuardValidator,
            @Value("${netherite.opa.token:}") String opaToken) {
        this.opaUrl = opaUrl;
        this.ssrfGuardValidator = ssrfGuardValidator;
        this.opaToken = opaToken;
        this.objectMapper = new ObjectMapper();

        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(2000, timeoutMs));
        factory.setReadTimeout((int) Math.min(5000, timeoutMs * 5));
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public boolean policyExists(String policyId) {
        if (!isOpaEndpointPermitted()) {
            return false;
        }
        try {
            HttpEntity<Void> request = new HttpEntity<>(buildHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    opaUrl + "/v1/policies/" + policyId, HttpMethod.GET, request, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (ResourceAccessException e) {
            log.warn("OPA policy query unreachable: {}", e.getMessage());
            return false;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value() == 200;
        } catch (Exception e) {
            log.warn("OPA policy query failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public OpaPolicyManageResult putPolicy(String policyId, String regoCode) {
        if (!isOpaEndpointPermitted()) {
            return OpaPolicyManageResult.failure(0, "OPA endpoint failed SSRF validation");
        }
        try {
            // OPA accepts the Rego source verbatim on PUT /v1/policies/<id>.
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            HttpEntity<String> request = new HttpEntity<>(regoCode, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    opaUrl + "/v1/policies/" + policyId, HttpMethod.PUT, request, Map.class);
            return toResult(response.getStatusCode().value(),
                    response.getBody() != null ? response.getBody().toString() : "ok");
        } catch (RestClientResponseException e) {
            return OpaPolicyManageResult.failure(e.getStatusCode().value(),
                    "OPA rejected policy: " + responseBodySnippet(e));
        } catch (ResourceAccessException e) {
            log.error("OPA manage endpoint unreachable: {}", e.getMessage());
            return OpaPolicyManageResult.failure(0, "OPA engine unavailable: " + e.getMessage());
        } catch (Exception e) {
            log.error("OPA policy push failed: {}", e.getMessage());
            return OpaPolicyManageResult.failure(0, "Policy push failed: " + e.getMessage());
        }
    }

    @Override
    public OpaPolicyManageResult deletePolicy(String policyId) {
        if (!isOpaEndpointPermitted()) {
            return OpaPolicyManageResult.failure(0, "OPA endpoint failed SSRF validation");
        }
        try {
            HttpEntity<Void> request = new HttpEntity<>(buildHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    opaUrl + "/v1/policies/" + policyId, HttpMethod.DELETE, request, Map.class);
            return toResult(response.getStatusCode().value(), "deleted");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return OpaPolicyManageResult.success(404, "already absent");
            }
            return OpaPolicyManageResult.failure(e.getStatusCode().value(),
                    "OPA rejected delete: " + responseBodySnippet(e));
        } catch (ResourceAccessException e) {
            log.error("OPA manage endpoint unreachable: {}", e.getMessage());
            return OpaPolicyManageResult.failure(0, "OPA engine unavailable: " + e.getMessage());
        } catch (Exception e) {
            return OpaPolicyManageResult.failure(0, "Policy delete failed: " + e.getMessage());
        }
    }

    @Override
    public OpaPolicyManageResult compileModule(String moduleName, String regoCode) {
        if (!isOpaEndpointPermitted()) {
            return OpaPolicyManageResult.failure(0, "OPA endpoint failed SSRF validation");
        }
        try {
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // /v1/compile requires a query; point it at the module's own package
            // so OPA type-checks the incoming rules (and their input usage), not
            // just their syntax.
            String query = "data." + packagePath(regoCode);
            String body = "{\"query\":" + objectMapper.writeValueAsString(query)
                    + ",\"modules\":{\"" + moduleName + "\":" + objectMapper.writeValueAsString(regoCode) + "}}";
            HttpEntity<String> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    opaUrl + "/v1/compile", HttpMethod.POST, request, Map.class);
            return toResult(response.getStatusCode().value(), "module compiles");
        } catch (RestClientResponseException e) {
            return OpaPolicyManageResult.failure(e.getStatusCode().value(),
                    "OPA rejected module: " + responseBodySnippet(e));
        } catch (ResourceAccessException e) {
            log.error("OPA compile endpoint unreachable: {}", e.getMessage());
            return OpaPolicyManageResult.failure(0, "OPA engine unavailable: " + e.getMessage());
        } catch (Exception e) {
            return OpaPolicyManageResult.failure(0, "Module compile failed: " + e.getMessage());
        }
    }

    private static String packagePath(String regoCode) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*(#.*)?$")
                .matcher(regoCode == null ? "" : regoCode);
        if (m.find()) {
            return m.group(1);
        }
        return "netherite.authz.decision";
    }

    @Override
    public OpaPolicyManageResult putData(String dataPath, String jsonBody) {
        if (!isOpaEndpointPermitted()) {
            return OpaPolicyManageResult.failure(0, "OPA endpoint failed SSRF validation");
        }
        try {
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    opaUrl + "/v1/data/" + dataPath, HttpMethod.PUT, request, Map.class);
            return toResult(response.getStatusCode().value(), "data written");
        } catch (RestClientResponseException e) {
            return OpaPolicyManageResult.failure(e.getStatusCode().value(),
                    "OPA rejected data: " + responseBodySnippet(e));
        } catch (ResourceAccessException e) {
            log.error("OPA data endpoint unreachable: {}", e.getMessage());
            return OpaPolicyManageResult.failure(0, "OPA engine unavailable: " + e.getMessage());
        } catch (Exception e) {
            return OpaPolicyManageResult.failure(0, "Data write failed: " + e.getMessage());
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (opaToken != null && !opaToken.isBlank()) {
            headers.setBearerAuth(opaToken);
        }
        return headers;
    }

    private boolean isOpaEndpointPermitted() {
        if (ssrfGuardValidator == null) {
            return true;
        }
        try {
            return ssrfGuardValidator.isUriPermittedForOperatorConfigured(URI.create(opaUrl));
        } catch (Exception e) {
            log.error("OPA endpoint URI invalid: {}", opaUrl, e);
            return false;
        }
    }

    private OpaPolicyManageResult toResult(int statusCode, String message) {
        return statusCode >= 200 && statusCode < 300
                ? OpaPolicyManageResult.success(statusCode, message)
                : OpaPolicyManageResult.failure(statusCode, message);
    }

    private static String responseBodySnippet(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return e.getMessage();
        }
        return body.length() > 400 ? body.substring(0, 400) : body;
    }
}