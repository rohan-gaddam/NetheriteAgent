package io.netherite.security.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHashServiceTest {

    private final ApiKeyHashService apiKeyHashService = new ApiKeyHashService();

    @Test
    @DisplayName("Should generate and verify API key securely")
    void testApiKeyGenerationAndVerification() {
        ApiKeyHashService.GeneratedApiKey key = apiKeyHashService.generateApiKey();

        assertThat(key.keyIdentifier()).startsWith("ntk_live_");
        assertThat(key.secretToken()).isNotBlank();
        assertThat(key.hashedSecret()).isNotBlank();
        assertThat(key.fullApiKey()).isEqualTo(key.keyIdentifier() + "." + key.secretToken());

        assertThat(apiKeyHashService.verifySecret(key.secretToken(), key.hashedSecret())).isTrue();
        assertThat(apiKeyHashService.verifySecret("wrong_secret", key.hashedSecret())).isFalse();
    }
}
