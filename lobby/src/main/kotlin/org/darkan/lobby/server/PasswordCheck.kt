package org.darkan.lobby.server

import org.darkan.core.crypt.Crypto
import org.darkan.core.model.Account
import org.darkan.core.mongo.collections.Accounts

suspend fun Account.passwordMatches(password: String): Boolean {
    val migrated = when {
        Crypto.isArgon2Hash(passwordHash) -> {
            if (!Crypto.verifyPasswordArgon2(password, passwordHash)) return false
            if (this.password == null && legacyPass == null) return true
            false
        }
        this.password != null -> {
            if (!Crypto.legacyCompare(password, this.password!!.map { it.toByte() }.toByteArray())) return false
            true
        }
        legacyPass != null -> {
            if (!Crypto.gigaLegacyCompare(password, legacyPass!!)) return false
            true
        }
        else -> return false
    }
    if (migrated) passwordHash = Crypto.hashPasswordArgon2(password)
    this.password = null
    legacyPass = null
    Accounts.save(this)
    return true
}
