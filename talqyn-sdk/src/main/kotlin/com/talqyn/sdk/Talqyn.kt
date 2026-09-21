package com.talqyn.sdk

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The Talqyn client: search, consultant, events.
 *
 * Create **one instance per app** — in `Application.onCreate` or your DI graph. It holds
 * the issued device token and the defaults shared by every request — locale, place, A/B
 * bucket — so a second instance would mint a second token and run a second reissue
 * schedule of its own.
 *
 * ```kotlin
 * val talqyn = Talqyn(context, TalqynConfiguration(
 *     credentials = TalqynDeviceTokenCredentials(
 *         storefront = "myshop",
 *         clientKeyId = "ck_3f9a1c2b7d4e",
 *         clientSecret = secret,
 *     ),
 *     baseUrl = BuildConfig.TALQYN_BASE_URL,
 *     defaultLocale = TalqynLocale.En,
 * ))
 *
 * val found = talqyn.search.search("iphone 15")
 * talqyn.consultant.ask("need a laptop for school").collect { event -> ... }
 * ```
 *
 * Nothing is sent during construction, and nothing is read from disk. The first request
 * mints the device token on the way; call [prepare] at launch to get that out of the way
 * ahead of time.
 */
public class Talqyn private constructor(
    configuration: TalqynConfiguration,
    userIdStore: TalqynUserIdStore,
) {
    /**
     * Creates a client whose shopper id lives in the app's no-backup directory, unless the
     * configuration names a [TalqynConfiguration.userIdStore] of its own.
     */
    public constructor(context: Context, configuration: TalqynConfiguration) :
        this(configuration, configuration.userIdStore ?: TalqynFileUserIdStore.noBackup(context))

    /**
     * Creates a client with no `Context` — for tests and for code that is not an app. The
     * configuration must name a [TalqynConfiguration.userIdStore]: without one there is
     * nowhere for the shopper id to survive a restart.
     */
    public constructor(configuration: TalqynConfiguration) :
        this(
            configuration,
            requireNotNull(configuration.userIdStore) {
                "TalqynConfiguration.userIdStore is required when the client is built without a Context"
            },
        )

    /**
     * The client's own work: minting, background reissue, fire-and-forget events. Outlives any
     * screen. Every job in it handles the failures it expects; the handler is for the ones
     * nobody expected, which must not crash an app that merely embeds the SDK.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            configuration.logHandler.log(TalqynLogEvent.Level.Error, "background work failed unexpectedly: ${error.javaClass.name}")
        },
    )

    private val defaults = TalqynDefaults(configuration)

    private val authorizer: TalqynDeviceTokenAuthorizer

    /** Instant search, the start screen, listings, and the filter panel. */
    public val search: TalqynSearchApi

    /** The consultant and the shopper's chat history. */
    public val consultant: TalqynConsultantApi

    /** Clicks and submitted queries. */
    public val events: TalqynEventsApi

    init {
        val transport = configuration.transport ?: TalqynUrlConnectionTransport()
        val builder = TalqynRequestBuilder(configuration.baseUrl, configuration.apiVersion, configuration.timeout)
        authorizer = TalqynDeviceTokenAuthorizer(
            credentials = configuration.credentials,
            store = userIdStore,
            minter = TalqynDeviceTokenMinter(builder, transport, configuration.logHandler),
            logHandler = configuration.logHandler,
            scope = scope,
        )
        val client = TalqynApiClient(
            builder = builder,
            transport = transport,
            authorizer = authorizer,
            retryPolicy = configuration.retryPolicy,
            streamTimeout = configuration.streamTimeout,
            logHandler = configuration.logHandler,
        )
        search = TalqynSearchApi(client, defaults)
        consultant = TalqynConsultantApi(client, defaults, configuration.logHandler)
        events = TalqynEventsApi(client, defaults, configuration.logHandler, scope)
    }

    // region Device token

    /**
     * Mints the device token ahead of time, typically at app launch.
     *
     * Without it the first request pays for the mint, and the shopper waits two round
     * trips instead of one.
     *
     * The failure is usually safe to ignore — the next request will try again — with one
     * exception: [TalqynException.Forbidden] means device-token issuance is not enabled for
     * the storefront, which will not start working on its own. That is a conversation with
     * Talqyn support.
     */
    public suspend fun prepare() {
        authorizer.prepare()
    }

    /**
     * Changes who the SDK acts as: sign-in, sign-out, "leave my history".
     *
     * The current token is discarded, because it names the previous shopper and turns
     * taken under it would land in **their** history. A request still waiting for a token
     * under the previous shopper is not sent: it fails with [TalqynException.IdentityChanged].
     */
    public suspend fun setIdentity(identity: TalqynDeviceIdentity) {
        authorizer.setIdentity(identity)
    }

    /**
     * The shopper the SDK currently acts as, or `null` for a guest — the consultant works, no history is recorded.
     *
     * @throws TalqynException When the stored shopper id cannot be read.
     */
    public suspend fun currentUserId(): String? = authorizer.currentUserId()

    /**
     * The identity the SDK currently acts under, as it was set. Unlike [currentUserId] this
     * does not change when a token is issued: it is the value to compare when deciding
     * whether the shopper changed.
     */
    public suspend fun currentIdentity(): TalqynDeviceIdentity = authorizer.currentIdentity()

    // endregion

    // region Request defaults

    /**
     * Sets the shopper's place for every subsequent request.
     *
     * Both values are ids of options from [TalqynSearchApi.filters] — the `city` and
     * `location` groups — that is, identifiers in **your** catalog's numbering.
     *
     * Changing the city **must** clear the store, which is why both travel in one call. A
     * store beats a city, so a new city paired with a stale store would apply the stale
     * one: the city change would appear to work while doing nothing.
     *
     * @param cityId The city, or `null` to search the whole country.
     * @param locationId The specific store, or `null` for the whole city.
     */
    public fun setPlace(cityId: String?, locationId: String? = null) {
        defaults.update { it.copy(cityId = cityId, locationId = locationId) }
    }

    /** Sets the locale of every subsequent request. */
    public fun setLocale(locale: TalqynLocale) {
        defaults.update { it.copy(locale = locale) }
    }

    /**
     * Sets the storefront's A/B bucket, echoed into Talqyn analytics so a pilot can be
     * compared against your previous search; it has no effect on results.
     *
     * @param variant The bucket label — `[A-Za-z0-9._:-]`, at most 32 characters — or
     *   `null` when no experiment is running.
     */
    public fun setVariant(variant: String?) {
        defaults.update { it.copy(variant = variant) }
    }

    /** The place currently applied to requests that name none. */
    public val currentPlace: TalqynPlace
        get() = defaults.current.let { TalqynPlace(it.cityId, it.locationId) }

    /** The locale currently applied to requests that name none. */
    public val currentLocale: TalqynLocale get() = defaults.current.locale

    // endregion

    public companion object {
        /**
         * The SDK's version. Sent with every request as `X-Talqyn-SDK`, so that a report of
         * "search broke in the app" can be narrowed to the builds it actually broke in.
         * Quote it when contacting Talqyn support. Generated from the version the SDK is
         * built and published under, so the two cannot disagree.
         */
        public const val VERSION: String = TALQYN_SDK_VERSION

        /**
         * The value of the `X-Talqyn-SDK` header: platform and version. Public for a
         * storefront that mints device tokens through its own transport — the header
         * identifies the client on those requests too.
         */
        public const val CLIENT_HEADER: String = "android/$VERSION"
    }
}

/**
 * The shopper's place, in your catalog's numbering.
 *
 * @property cityId The city, or `null` for the whole country.
 * @property locationId The specific store, or `null` for the whole city.
 */
public data class TalqynPlace(
    val cityId: String?,
    val locationId: String?,
)
