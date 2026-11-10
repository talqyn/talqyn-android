package com.talqyn.sdk

import java.util.Locale
import java.util.UUID

/**
 * The storefront client key shipped inside an app build.
 *
 * The SDK authenticates one way: a client key (`id` + `secret`) signs a call to
 * `POST /v1/consultant/token`, and every subsequent request travels under the
 * short-lived `tlqd_` token it returns. The token lives for minutes, grants
 * exactly three scopes (consultant, search, and the events of its own shopper),
 * has its own rate-limit bucket, and names the shopper it belongs to — which is
 * what makes per-shopper chat history possible at all.
 *
 * The tenant's `tlq_` key has no place in an app and is deliberately not accepted
 * here: it stops being a secret on release day (it can be extracted from the
 * APK), it can only be revoked by shipping a new build, and it shares a single
 * per-minute bucket across every installation. It stays a backend credential.
 *
 * The secret never travels over the network: a mint request carries only the key
 * id, a timestamp, a nonce, and an HMAC over the request body
 * ([TalqynClientSignature]). Revocation and rotation are scoped to a single
 * storefront — a new key goes out with the next build while the old one keeps
 * working until the last build carrying it has updated.
 *
 * @property storefront The storefront slug issued during onboarding. Addressing, not a secret.
 * @property clientKeyId The client key id. Sent in the `X-Client-Key` header.
 * @property clientSecret The client key secret. Never transmitted; used only to sign requests.
 * @property identity Who the issued token names as the shopper.
 */
public data class TalqynDeviceTokenCredentials(
    val storefront: String,
    val clientKeyId: String,
    val clientSecret: String,
    val identity: TalqynDeviceIdentity = TalqynDeviceIdentity.PersistentAnonymous,
) {
    /** The secret — and the shopper id, see [TalqynDeviceIdentity.User] — stay out of logs and crash reports that print the configuration. */
    override fun toString(): String =
        "TalqynDeviceTokenCredentials(storefront=$storefront, clientKeyId=$clientKeyId, clientSecret=***, identity=$identity)"
}

/**
 * Who a device token names as the shopper.
 *
 * Exactly one thing depends on this, and it is a significant one: chat history. A
 * guest has none — conversations stay anonymous forever — whereas a named shopper
 * gets a history that reopens under the same id on the next launch. This is why
 * the id **must survive app restarts**.
 *
 * A named shopper's id is yours, in your own format. It is asserted by the device
 * and confirmed by nobody, so being unguessable is the only thing protecting
 * somebody else's conversations — and choosing such an id is the storefront's
 * responsibility. A random one (a UUID, an opaque account id) is fine; an
 * enumerable one such as a CRM row number exposes other shoppers' history to
 * anyone who substitutes it. If your account ids are enumerable, map each to a
 * stable random id on your backend and pass that through [User].
 */
public sealed interface TalqynDeviceIdentity {
    /** A guest token: no `user_id` is sent. The consultant works, no history is recorded. */
    public data object Guest : TalqynDeviceIdentity

    /** A persistent anonymous UUID, generated on first use and kept in the configured [TalqynUserIdStore]. */
    public data object PersistentAnonymous : TalqynDeviceIdentity

    /**
     * An explicit shopper id, for example the account's id from your backend after
     * sign-in: 1 to 128 characters of `[A-Za-z0-9._:-]`.
     *
     * A UUID is lowercased, as the server does, so that one account written two ways
     * stays one shopper. Any other id is sent exactly as given and is case-sensitive.
     *
     * @throws TalqynException.InvalidConfiguration when the id has another form.
     */
    public class User(id: String) : TalqynDeviceIdentity {
        /** The id as it goes on the wire. */
        public val id: String = wireShopperId(id)
            ?: throw TalqynException.InvalidConfiguration("a shopper id must be 1 to 128 characters of [A-Za-z0-9._:-]")

        /** An explicit shopper id that is a UUID. */
        public constructor(id: UUID) : this(id.toString())

        override fun equals(other: Any?): Boolean = other is User && other.id == id

        override fun hashCode(): Int = id.hashCode()

        /** The id stays out of logs: being unguessable is all that keeps this shopper's history theirs. */
        override fun toString(): String = "User(id=***)"
    }
}

private val shopperIdForm = Regex("^[A-Za-z0-9._:-]{1,128}$")

/** The id as the server will store it, or `null` when the server would refuse it. */
internal fun wireShopperId(raw: String): String? = when {
    !shopperIdForm.matches(raw) -> null
    parseCanonicalUuid(raw) != null -> raw.lowercase(Locale.ROOT)
    else -> raw
}
