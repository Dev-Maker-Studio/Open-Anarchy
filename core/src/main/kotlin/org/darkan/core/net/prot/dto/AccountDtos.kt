/**
 * Payload types carried by the account packets.
 */
package org.darkan.core.net.prot.dto

/**
 * Response codes for the `CreateAccountReply` packet (opcode 87).
 *
 * These are the codes the client resolves through its `CreateAccountRequest` table; a code it
 * does not recognise falls through to its generic unknown-error state.
 */
enum class CreateAccountReplyOpcode(val value: Int) {
    SUCCESS(2),
    UNKNOWN_ERROR(3),
    USERNAME_UNAVAILABLE(9),
    INVALID_USERNAME(10),
    INVALID_PASSWORD(20),
    PASSWORD_TOO_SIMILAR(21),
    INVALID_DOB(30),
    EMAIL_ALREADY_IN_USE(31),
    INVALID_EMAIL(32),
    TERMS_NOT_ACCEPTED(33),
    TOO_MANY_ATTEMPTS(34),
    SERVER_UNAVAILABLE(38)
}
