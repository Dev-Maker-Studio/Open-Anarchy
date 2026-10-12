package org.darkan.core.net.prot.dto

enum class CreateAccountReplyOpcode(val value: Int) {
    SUCCESS(2),
    SERVER_ERROR(3),
    SERVER_BUSY(7),
    CREATION_UNAVAILABLE(9),
    EMAIL_ALREADY_IN_USE(20),
    INVALID_EMAIL(21),
    PASSWORD_LENGTH(30),
    PASSWORD_NOT_ALPHANUMERIC(31),
    PASSWORD_TOO_EASY(33),
    INVALID_PASSWORD(34),
    CLIENT_OUTDATED(37),
    SERVER_UNAVAILABLE(38)
}

enum class CheckEmailReplyOpcode(val value: Int) {
    VALID(2),
    SERVER_ERROR(3),
    EMAIL_ALREADY_IN_USE(20),
    INVALID_EMAIL(21)
}
