package io.netherite.api.model.control;

/**
 * WS6 — request to designate an organization's FIRST authorized person.
 *
 * <p>Only meaningful for an organization that has none. A second bootstrap is
 * refused: once an organization has an authorized person, the only way to change
 * it is an explicit transfer by that person.</p>
 *
 * @param userId the active member of this organization to designate
 */
public record BootstrapAuthorizedPersonRequest(String userId) {
}
