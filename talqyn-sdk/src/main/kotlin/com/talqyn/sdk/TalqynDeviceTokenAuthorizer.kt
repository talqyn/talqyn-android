package com.talqyn.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Mints, caches, and reissues the device token.
 *
 * A token lives for minutes. Reissuing is scheduled from [TalqynDeviceToken.expiresIn]
 * and runs **in the background** while the current token is still good: no request
 * waits for a reissue, and a reissue that fails is a log line rather than a failed
 * search — the current token keeps working until it actually expires. Timed from the
 * **local** clock at the moment of the response: `expires_at` runs on the server's
 * clock, and device clocks drift.
 *
 * Guarded by a mutex because screens race for the token: search and the consultant
 * open at once, and that has to mint once, not twice. The mint itself runs in the
 * client's own scope, so a caller that goes away does not cancel it for the others —
 * and its outcome is installed there as well, whether or not anybody is still waiting.
 */
internal class TalqynDeviceTokenAuthorizer(
    private val credentials: TalqynDeviceTokenCredentials,
    private val store: TalqynUserIdStore,
    private val minter: TalqynDeviceTokenMinter,
    private val logHandler: ((TalqynLogEvent) -> Unit)?,
    private val scope: CoroutineScope,
    /** The clock, in epoch milliseconds. Injected so the lifecycle can be tested without waiting it out. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the store is read and written: it may touch the disk. */
    private val storeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Issued(val token: TalqynDeviceToken, issuedAt: Long) {
        /** When to start fetching the next token, ahead of expiry. */
        val refreshAt: Long

        /** When this token stops working, by the local clock. */
        val expiresAt: Long

        // The refresh lead is a fifth of the lifetime, floored at 15 s and capped at
        // two minutes: a bare percentage would reissue on every request for a short
        // token, and hold a long one far past its use.
        init {
            val life = max(token.expiresIn.toDouble(DurationUnit.SECONDS), 1.0)
            val lead = min(max(life * 0.2, 15.0), 120.0)
            refreshAt = issuedAt + (max(life - lead, 1.0) * 1000).toLong()
            expiresAt = issuedAt + (life * 1000).toLong()
        }
    }

    private class Blocked(val until: Long, val error: TalqynException)

    private val mutex = Mutex()

    private var identity: TalqynDeviceIdentity = credentials.identity
    private var issued: Issued? = null
    private var inFlight: Deferred<TalqynDeviceTokenMinter.Minted>? = null
    private var blocked: Blocked? = null
    private var nextBackgroundReissueAt: Long? = null

    /** Bumped on every identity change. A mint that started under a previous value belongs to a previous shopper, however it ends. */
    private var generation = 0

    /**
     * Server clock minus device clock, learned from a rejected mint and kept in the
     * store, so the next mint — in this launch or the next — signs with the right time
     * from its first attempt. Read from the store on the first mint, not at
     * construction: the client is built on the main thread.
     */
    private var clockOffsetMillis: Long? = null

    /** The `Authorization` header for the next request, minting or reissuing the token on the way when needed. */
    suspend fun headers(): Map<String, String> = mapOf("Authorization" to authorization(token()))

    /**
     * The server rejected the token (401): drops it, but only if it is the one that was
     * rejected. Two requests racing past expiry both see a 401; by the time the second
     * one reports it, the first has already minted a replacement that must survive.
     *
     * @param authorization The `Authorization` header value the rejected request carried.
     */
    suspend fun invalidate(authorization: String?) {
        mutex.withLock {
            val current = issued ?: return
            if (authorization != null && authorization != authorization(current.token)) return
            issued = null
        }
    }

    /** The server's canonical form is lowercase; the local fallback is lowercased too, so the value does not change casing once a token exists. */
    suspend fun currentUserId(): String? = mutex.withLock {
        issued?.token?.userId ?: resolveUserId()?.toString()?.lowercase(Locale.ROOT)
    }

    /** Who the SDK acts as, as the app set it. */
    suspend fun currentIdentity(): TalqynDeviceIdentity = mutex.withLock { identity }

    /**
     * Changes the shopper. The issued token is discarded: it names the previous one, and turns
     * under it would land in their history. A request waiting for a token under the previous
     * shopper fails with [TalqynException.IdentityChanged].
     */
    suspend fun setIdentity(identity: TalqynDeviceIdentity) {
        mutex.withLock {
            if (identity == this.identity) return
            this.identity = identity
            generation += 1
            issued = null
            blocked = null
            nextBackgroundReissueAt = null
            inFlight?.cancel()
            inFlight = null
        }
    }

    suspend fun prepare(): TalqynDeviceToken = token()

    private suspend fun token(): TalqynDeviceToken {
        val (task, generation) = mutex.withLock {
            val now = now()
            val current = issued
            if (current != null && now < current.expiresAt) {
                // Past the refresh mark but still good: fetch the next one in the
                // background and serve this one. Nobody waits, and a reissue that fails
                // costs nothing until this token runs out.
                if (now >= current.refreshAt && inFlight == null && !isBlocked(now) && !isBackingOff(now)) {
                    try {
                        startMint()
                    } catch (e: TalqynException) {
                        // The store could not be read. This token keeps serving; the next
                        // request after the pause tries again.
                        nextBackgroundReissueAt = now + REISSUE_RETRY_DELAY_MILLIS
                        logHandler.log(TalqynLogEvent.Level.Warning, "device token reissue could not start (${e.message})")
                    }
                }
                return current.token
            }
            blocked?.let { refusal ->
                if (now < refusal.until) throw refusal.error
                blocked = null
            }
            (inFlight ?: startMint()) to this.generation
        }
        return settle(task, generation)
    }

    /**
     * Starts a mint together with the settler that installs its outcome.
     *
     * The bookkeeping cannot be left to the callers: `await()` gives up the moment its caller is
     * cancelled, so a mint that every caller walked away from would stay in [inFlight] — and be
     * installed much later, by whoever asked next, as if it had just been issued.
     */
    private suspend fun startMint(): Deferred<TalqynDeviceTokenMinter.Minted> {
        val userId = resolveUserId()
        val offset = loadClockOffset()
        val credentials = credentials
        val generation = this.generation
        val task = scope.async {
            minter.mint(
                storefront = credentials.storefront,
                clientKeyId = credentials.clientKeyId,
                clientSecret = credentials.clientSecret,
                userId = userId,
                clockOffsetMillis = offset,
            )
        }
        inFlight = task
        scope.launch {
            try {
                settle(task, generation)
            } catch (e: Exception) {
                // The outcome is delivered to whoever waits for it, and logged by `settle` when a live token is affected.
            }
        }
        return task
    }

    /**
     * Waits for a mint and installs its outcome. Every waiter — a request, `prepare()`,
     * the settler started with the mint — comes through here; the first to resume does
     * the bookkeeping, and the rest find `inFlight` already cleared.
     */
    private suspend fun settle(task: Deferred<TalqynDeviceTokenMinter.Minted>, generation: Int): TalqynDeviceToken {
        val outcome: Result<TalqynDeviceTokenMinter.Minted> = try {
            Result.success(task.await())
        } catch (e: CancellationException) {
            // The waiter itself was cancelled — then this rethrows — or the mint was, by a change of shopper.
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

        mutex.withLock {
            // The shopper may have changed while the mint was in flight — a response
            // already received still completes. That token names the previous shopper:
            // it must not be installed, or the next turn would land in their history.
            if (generation != this.generation) throw TalqynException.IdentityChanged()
            val minted = outcome.getOrNull()
            if (minted != null) {
                if (inFlight === task) {
                    inFlight = null
                    nextBackgroundReissueAt = null
                    issued = Issued(minted.token, now())
                    logHandler.log(
                        TalqynLogEvent.Level.Debug,
                        "device token issued for ${minted.token.expiresIn.inWholeSeconds}s, guest=${minted.token.userId == null}",
                    )
                    if (minted.clockOffsetMillis != (clockOffsetMillis ?: 0L)) {
                        val offset = minted.clockOffsetMillis
                        clockOffsetMillis = offset
                        // Kept even if this waiter is cancelled meanwhile: the token is installed
                        // already, and the next launch must not pay again for the skew just learned.
                        withContext(NonCancellable + storeDispatcher) {
                            try {
                                store.saveClockOffset(if (offset == 0L) null else offset.milliseconds)
                            } catch (e: Exception) {
                                logHandler.log(TalqynLogEvent.Level.Warning, "device clock correction was not saved (${e.javaClass.simpleName})")
                            }
                        }
                    }
                }
                return minted.token
            }

            val error = outcome.exceptionOrNull() ?: IllegalStateException("a mint ended with neither a token nor a failure")
            // A mint cancelled under the same shopper was cancelled by nothing this class does —
            // the client's scope shutting down — and says nothing about the storefront.
            val failure = if (error is CancellationException) TalqynException.Transport(error) else TalqynException.wrap(error)
            if (inFlight === task) {
                inFlight = null
                val now = now()
                if (failure.isRetryable) {
                    nextBackgroundReissueAt = now + REISSUE_RETRY_DELAY_MILLIS
                } else {
                    blocked = Blocked(now + FAILURE_COOLDOWN_MILLIS, failure)
                }
                issued?.let { current ->
                    val left = current.expiresAt - now
                    if (left > 0) {
                        logHandler.log(
                            TalqynLogEvent.Level.Warning,
                            "device token reissue failed (${failure.message}); the current token is good for another ${left / 1000}s",
                            failure.requestId,
                        )
                    }
                }
            }
            throw failure
        }
    }

    private fun isBlocked(now: Long): Boolean = blocked?.let { now < it.until } ?: false

    private fun isBackingOff(now: Long): Boolean = nextBackgroundReissueAt?.let { now < it } ?: false

    private suspend fun loadClockOffset(): Long = clockOffsetMillis
        ?: fromStore { store.loadClockOffset()?.inWholeMilliseconds ?: 0L }.also { clockOffsetMillis = it }

    /** `PersistentAnonymous` generates the UUID on first use and stores it: without an id that survives a restart there is no chat history. */
    private suspend fun resolveUserId(): UUID? = when (val identity = identity) {
        TalqynDeviceIdentity.Guest -> null
        is TalqynDeviceIdentity.User -> identity.id
        TalqynDeviceIdentity.PersistentAnonymous -> fromStore { store.loadUserId() ?: UUID.randomUUID().also(store::saveUserId) }
    }

    /**
     * Reads or writes the store off the caller's thread.
     *
     * A store that fails — a file that cannot be read right now — fails the token the way a
     * transport failure would: it is retried, and never answered by inventing a new shopper in
     * place of an id that merely could not be read this time.
     */
    private suspend fun <T> fromStore(access: () -> T): T = withContext(storeDispatcher) {
        try {
            access()
        } catch (e: Exception) {
            throw TalqynException.wrap(e)
        }
    }

    private companion object {
        /**
         * Pause after an unrecoverable refusal (403 "not enabled", 501 "not configured").
         * Without it every screen would hammer the endpoint for the same answer.
         */
        const val FAILURE_COOLDOWN_MILLIS = 60_000L

        /**
         * Pause between **background** reissue attempts after a transient failure.
         * Without it a 503 on the mint endpoint would cost one mint per request — one
         * per keystroke, for a search field — for as long as the refresh window lasts.
         * A request whose token has actually expired is not held back by this: it
         * needs a token and waits for the mint.
         */
        const val REISSUE_RETRY_DELAY_MILLIS = 5_000L

        fun authorization(token: TalqynDeviceToken): String = "Bearer ${token.token}"
    }
}
