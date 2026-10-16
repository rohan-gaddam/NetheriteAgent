package io.netherite.api.model.control;

/**
 * WS6 — request to transfer an organization's authorized person.
 *
 * @param userId               the active member of this organization to designate
 * @param expectedCurrentUserId the designation the caller believes is current.
 *                             Re-checked under the organization row lock so two
 *                             simultaneous transfers cannot both succeed; the
 *                             loser receives 409 rather than silently overwriting.
 */
public record TransferAuthorizedPersonRequest(String userId, String expectedCurrentUserId) {
}
