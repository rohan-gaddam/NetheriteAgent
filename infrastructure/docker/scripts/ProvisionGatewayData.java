import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCrypt;

public class ProvisionGatewayData {
    public static void main(String[] args) throws Exception {
        SecureRandom random = new SecureRandom();

        // 1. Generate API key
        String keyIdentifier = "ntk_live_agentbridge";
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secretToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String fullApiKey = keyIdentifier + "." + secretToken;
        String hashedSecret = BCrypt.hashpw(secretToken, BCrypt.gensalt(12));

        // 2. Read encryption key
        File encKeyFile = new File("infrastructure/docker/secrets/credential_encryption_key");
        String encKeyB64 = Files.readString(encKeyFile.toPath()).trim();
        byte[] encKeyBytes = Base64.getDecoder().decode(encKeyB64);
        SecretKeySpec keySpec = new SecretKeySpec(encKeyBytes, "AES");

        // 3. Encrypt credentials JSON
        String credentialsJson = "{\"email\":\"erp@webvella.com\",\"password\":\"erp\"}";
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(credentialsJson.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(12 + ciphertext.length);
        buffer.put(iv);
        buffer.put(ciphertext);
        String encryptedCredentials = Base64.getEncoder().encodeToString(buffer.array());

        // Output SQL and key to a secure temporary local file
        String sql = String.format(
            "BEGIN;\n" +
            "DELETE FROM agent_resource_bindings WHERE agent_id = 'a0000000-0000-0000-0000-000000000001' OR resource_id = 'b0000000-0000-0000-0000-000000000001';\n" +
            "DELETE FROM agent_api_keys WHERE key_identifier = '%s';\n" +
            "DELETE FROM agents WHERE id = 'a0000000-0000-0000-0000-000000000001' OR name = 'AgentBridge';\n" +
            "DELETE FROM resources WHERE id = 'b0000000-0000-0000-0000-000000000001' OR name = 'ai-erp';\n" +
            "INSERT INTO agents (id, organization_id, name, description, trust_tier, status) VALUES ('a0000000-0000-0000-0000-000000000001', '1a3e16e1-992a-4806-bcd5-fa5fd8c2af77', 'AgentBridge', 'AgentBridge ERP Gateway Integration', 'TIER_1', 'ACTIVE');\n" +
            "INSERT INTO agent_api_keys (id, agent_id, organization_id, key_identifier, secret_hash, description) VALUES (uuid_generate_v4(), 'a0000000-0000-0000-0000-000000000001', '1a3e16e1-992a-4806-bcd5-fa5fd8c2af77', '%s', '%s', 'AgentBridge production API key');\n" +
            "INSERT INTO resources (id, organization_id, name, connector_type, base_url, encrypted_credentials, sensitivity_level, description, status) VALUES ('b0000000-0000-0000-0000-000000000001', '1a3e16e1-992a-4806-bcd5-fa5fd8c2af77', 'ai-erp', 'AI_ERP', 'http://host.docker.internal:5080', '%s', 'LOW', 'AI-ERP production backend', 'ACTIVE');\n" +
            "INSERT INTO agent_resource_bindings (id, organization_id, agent_id, resource_id, allowed_actions, is_active) VALUES (uuid_generate_v4(), '1a3e16e1-992a-4806-bcd5-fa5fd8c2af77', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', '[\"*\"]'::jsonb, true);\n" +
            "COMMIT;\n",
            keyIdentifier, keyIdentifier, hashedSecret, encryptedCredentials
        );

        Files.writeString(new File("provision.sql").toPath(), sql, StandardCharsets.UTF_8);
        Files.writeString(new File("agent_key.txt").toPath(), fullApiKey, StandardCharsets.UTF_8);
        System.out.println("PROVISION_GENERATED_SUCCESS");
    }
}
