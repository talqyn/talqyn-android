package com.talqyn.sdk

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A device token issued by `POST /v1/consultant/token`.
 *
 * [Talqyn] mints, caches, and reissues these on its own; the type is public so an app
 * can inspect what it is running under.
 *
 * @property token The token string, sent as `Authorization: Bearer tlqd_…`.
 * @property expiresAt When the token expires, by the **server's** clock. Use [expiresIn]
 *   to decide when to reissue: device clocks drift, and the SDK counts from local time at
 *   the moment the response arrived.
 * @property expiresIn How long the token lives from the moment it was issued.
 * @property userId The shopper the token is bound to, in canonical form. `null` means a
 *   guest token: the consultant works, no history is recorded.
 */
public data class TalqynDeviceToken(
    val token: String,
    val expiresAt: Instant?,
    val expiresIn: Duration,
    val userId: String?,
) {
    /** The token stays out of logs. */
    override fun toString(): String = "TalqynDeviceToken(expiresAt=$expiresAt, expiresIn=$expiresIn, guest=${userId == null})"

    internal companion object {
        fun decode(json: JsonObject): TalqynDeviceToken = TalqynDeviceToken(
            token = json.string("token") ?: throw TalqynJsonException("mint response carries no token"),
            expiresAt = json.instant("expires_at"),
            expiresIn = (json.double("expires_in") ?: 900.0).seconds,
            userId = json.string("user_id"),
        )
    }
}
