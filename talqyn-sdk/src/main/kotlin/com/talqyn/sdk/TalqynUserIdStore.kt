package com.talqyn.sdk

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Storage for what the SDK has to remember between launches: the persistent
 * anonymous shopper UUID and the device clock correction.
 *
 * The id **must** survive app restarts: it is the only thing that reopens a
 * shopper's conversations. It should **not** survive reinstallation — a new id
 * simply has no history, which is preferable to handing one device owner the
 * conversations of the previous one.
 *
 * The clock correction is a convenience: without it a device with a skewed clock
 * pays one rejected mint per launch to learn the skew again. The two clock
 * methods have default implementations that remember nothing, so a store written
 * for the id alone keeps working.
 *
 * Implement this to keep the values somewhere of your own; the SDK ships
 * [TalqynFileUserIdStore], [TalqynSharedPreferencesUserIdStore], and
 * [TalqynInMemoryUserIdStore]. Calls come from background threads, one at a time.
 *
 * A store may throw — an `IOException` for storage that cannot be read right now. Do
 * that rather than answer `null`: `null` means "no shopper yet", and the SDK answers it
 * with a new UUID, which orphans the history of the id that merely failed to read. A
 * failure is reported as a failed request, and the next request reads again.
 */
public interface TalqynUserIdStore {
    /** Returns the stored shopper UUID, or `null` if none has been stored yet. */
    public fun loadUserId(): UUID?

    /** Stores the shopper UUID, replacing any previous value; `null` forgets it. */
    public fun saveUserId(id: UUID?)

    /** Returns the stored device clock correction, or `null` if none has been stored yet. */
    public fun loadClockOffset(): Duration? = null

    /** Stores the device clock correction — server clock minus device clock; `null` forgets it. */
    public fun saveClockOffset(offset: Duration?) {}
}

/**
 * A [TalqynUserIdStore] backed by a small file. The default, in the app's
 * no-backup directory — see [noBackup].
 *
 * Deliberately not `SharedPreferences`: Android's Auto Backup restores those on a
 * new install, which would hand a reinstalling device — or a new phone — the
 * previous owner's history. The shopper id is not a secret either: it is asserted
 * by the device and verified by nobody. If history has to follow a shopper across
 * devices, issue the UUID from your backend and pass it through
 * [TalqynDeviceIdentity.User] instead.
 *
 * A file that exists but cannot be read makes the store throw instead of reporting no
 * id; content that does not parse counts as no id. Every write re-reads the file first,
 * so two stores over one file — two clients, two processes — do not erase each other's
 * values, and lands whole: written aside, synced to disk, moved into place.
 */
public class TalqynFileUserIdStore private constructor(locate: () -> File) : TalqynUserIdStore {
    /** Creates a store over a file of your choosing. Nothing is read until first use. */
    public constructor(file: File) : this({ file })

    // Located on first use, not at construction: the client is typically built in
    // `Application.onCreate`, on the main thread, where the disk has no business.
    private val file by lazy(locate)
    private val lock = Any()
    private var loaded: Properties? = null

    override fun loadUserId(): UUID? = synchronized(lock) {
        properties().getProperty(USER_ID)?.let(::parseCanonicalUuid)
    }

    override fun saveUserId(id: UUID?) {
        synchronized(lock) {
            update { if (id == null) remove(USER_ID) else setProperty(USER_ID, id.toString()) }
        }
    }

    override fun loadClockOffset(): Duration? = synchronized(lock) {
        properties().getProperty(CLOCK_OFFSET)?.toLongOrNull()?.milliseconds
    }

    override fun saveClockOffset(offset: Duration?) {
        synchronized(lock) {
            update {
                if (offset == null) remove(CLOCK_OFFSET) else setProperty(CLOCK_OFFSET, offset.inWholeMilliseconds.toString())
            }
        }
    }

    /** Cached once read. A read that failed is not: the next call reads the file again. */
    private fun properties(): Properties = loaded ?: read().also { loaded = it }

    /**
     * The file as it is on disk now.
     *
     * @throws IOException when the file exists but cannot be read.
     */
    private fun read(): Properties {
        val properties = Properties()
        if (!file.exists()) return properties
        try {
            file.inputStream().use { properties.load(it) }
        } catch (e: IllegalArgumentException) {
            // Malformed content: not a failed read, and it will read no better next time.
            return Properties()
        }
        return properties
    }

    /** Changes what is on disk now, not this instance's copy of it. */
    private fun update(change: Properties.() -> Unit) {
        val properties = read().apply(change)
        write(properties)
        loaded = properties
    }

    /** Written aside and moved into place, so a process killed mid-write leaves the old file whole; synced first, so a power loss after the move does not leave an empty one. */
    private fun write(properties: Properties) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { out ->
            properties.store(out, null)
            out.fd.sync()
        }
        if (!temporary.renameTo(file) && !(file.delete() && temporary.renameTo(file))) {
            throw IOException("could not replace $file")
        }
    }

    public companion object {
        private const val USER_ID = "user_id"
        private const val CLOCK_OFFSET = "clock_offset"

        /**
         * A store in `Context.getNoBackupFilesDir()`: kept across launches and
         * updates, left out of Auto Backup, gone with the app.
         */
        @JvmStatic
        public fun noBackup(context: Context): TalqynFileUserIdStore {
            val application = context.applicationContext
            return TalqynFileUserIdStore { File(application.noBackupFilesDir, "talqyn/identity.properties") }
        }
    }
}

/**
 * A [TalqynUserIdStore] backed by `SharedPreferences` — for an app that already
 * keeps an anonymous id there and wants the SDK to adopt it.
 *
 * Point [key] at the existing key and the history already recorded under that id
 * stays reachable. Keep the preferences file out of backups — `android:dataExtractionRules`
 * for Android 12 and newer, `android:fullBackupContent` for Android 8 to 11: restored on a
 * new install, the id would open the previous owner's conversations.
 *
 * @param preferences The preferences to use.
 * @param key The key of the shopper id. The clock correction lives under the same key
 *   with a `.clock_offset` suffix, so two stores with different keys do not share one.
 */
public class TalqynSharedPreferencesUserIdStore(
    private val preferences: SharedPreferences,
    private val key: String = "talqyn.user_id",
) : TalqynUserIdStore {
    private val clockOffsetKey: String get() = "$key.clock_offset"

    override fun loadUserId(): UUID? = preferences.getString(key, null)?.let(::parseCanonicalUuid)

    override fun saveUserId(id: UUID?) {
        preferences.edit().apply { if (id == null) remove(key) else putString(key, id.toString()) }.apply()
    }

    override fun loadClockOffset(): Duration? =
        if (preferences.contains(clockOffsetKey)) preferences.getLong(clockOffsetKey, 0).milliseconds else null

    override fun saveClockOffset(offset: Duration?) {
        preferences.edit().apply {
            if (offset == null) remove(clockOffsetKey) else putLong(clockOffsetKey, offset.inWholeMilliseconds)
        }.apply()
    }
}

/**
 * A [TalqynUserIdStore] that keeps its values in memory only.
 *
 * For tests, and for storefronts that manage the shopper id themselves and only
 * need the SDK to hold it for the lifetime of the process.
 */
public class TalqynInMemoryUserIdStore(id: UUID? = null) : TalqynUserIdStore {
    private val lock = Any()
    private var id: UUID? = id
    private var clockOffset: Duration? = null

    override fun loadUserId(): UUID? = synchronized(lock) { id }

    override fun saveUserId(id: UUID?) {
        synchronized(lock) { this.id = id }
    }

    override fun loadClockOffset(): Duration? = synchronized(lock) { clockOffset }

    override fun saveClockOffset(offset: Duration?) {
        synchronized(lock) { clockOffset = offset }
    }
}

private val canonicalUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** `UUID.fromString` accepts `1-2-3-4-5`; a stored id that is not a real UUID reads as absent. */
internal fun parseCanonicalUuid(raw: String): UUID? =
    if (canonicalUuid.matches(raw)) UUID.fromString(raw) else null
