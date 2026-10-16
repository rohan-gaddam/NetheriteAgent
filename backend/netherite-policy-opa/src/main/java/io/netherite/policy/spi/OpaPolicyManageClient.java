package io.netherite.policy.spi;

import io.netherite.policy.model.OpaPolicyManageResult;

/**
 * Management-plane operations for synchronizing policy content into Open Policy
 * Agent. Distinct from {@link OpaPolicyEngineClient} (decision evaluation):
 * every operation mutates or inspects the policy store and therefore requires
 * the engine's administrative bearer credential.
 *
 * Fail-closed: transport errors, authorization failures and engine rejects are
 * reported via {@link OpaPolicyManageResult#failure(int, String)} — never
 * swallowed into success.
 */
public interface OpaPolicyManageClient {

    /**
     * Queries whether a policy module with the given id already exists.
     * 2xx → true; 404 (or any other status / error) → false.
     */
    boolean policyExists(String policyId);

    /**
     * Creates or replaces a policy module. On any non-2xx result the engine
     * rejected the module (conflict, compile failure, authorization…) and the
     * caller must treat the operation as failed.
     */
    OpaPolicyManageResult putPolicy(String policyId, String regoCode);

    /**
     * Removes a previously pushed policy module. 2xx → success; 404 is also
     * considered success (already absent).
     */
    OpaPolicyManageResult deletePolicy(String policyId);

    /**
     * Pre-flight validation of a module against the engine's compiler without
     * installing it. The engine reports syntax/type errors as non-2xx.
     */
    OpaPolicyManageResult compileModule(String moduleName, String regoCode);

    /**
     * Pushes a plain data document (JSON) into the engine's data store.
     */
    OpaPolicyManageResult putData(String dataPath, String jsonBody);
}