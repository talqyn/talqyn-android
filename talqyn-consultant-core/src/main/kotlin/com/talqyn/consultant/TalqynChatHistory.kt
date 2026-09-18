package com.talqyn.consultant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.talqyn.sdk.Talqyn
import com.talqyn.sdk.TalqynChatSummary
import com.talqyn.sdk.TalqynException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * The shopper's list of conversations: paged loading, refresh, deletion.
 *
 * Backs the history screen in `talqyn-ui`; public for a storefront that lists chats in its
 * own screen. Main-thread bound, like [TalqynConversation].
 *
 * @param talqyn The client to load through.
 * @param pageSize How many chats per page.
 * @param scope Where loading runs. The list works inside a scope of its own within this one, so
 *   [close] leaves the rest of the scope running. Defaults to a scope of its own on the main thread.
 */
public class TalqynChatHistory(
    public val talqyn: Talqyn,
    private val pageSize: Int = 20,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** What the list shows. */
    public sealed interface State {
        public data object Loading : State

        public data object Empty : State

        public data class Failed(val error: TalqynException) : State

        public data class Loaded(val chats: List<TalqynChatSummary>, val isLoadingMore: Boolean) : State
    }

    /** The list's own work, a child of the scope it was given: [close] ends this and nothing else. */
    private val work: CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val mutableState = MutableStateFlow<State>(State.Loading)

    /** What the list shows. */
    public val state: StateFlow<State> = mutableState.asStateFlow()

    private var chats: List<TalqynChatSummary> = emptyList()
    private var isLoadingPage = false
    private var hasMore = true
    private var loadJob: Job? = null

    /** Loads the first page, replacing what is shown. */
    public fun load() {
        loadPage(reset = true)
    }

    /** Loads the next page once the shopper is near the end of the list. */
    public fun loadMoreIfNeeded(after: TalqynChatSummary) {
        val index = chats.indexOfFirst { it.sessionId == after.sessionId }
        if (index < 0 || index < chats.size - 5) return
        loadPage(reset = false)
    }

    /**
     * Deletes a conversation. The row goes at once; a failed deletion reloads the list so the row
     * comes back. A deletion on its way is carried out even if the list is closed meanwhile.
     */
    public fun delete(sessionId: String) {
        if (!work.isActive) return
        chats = chats.filter { it.sessionId != sessionId }
        render()
        work.launch {
            try {
                // The shopper confirmed the deletion; a screen going away while the request is out
                // does not take that back.
                withContext(NonCancellable) { talqyn.consultant.deleteChat(sessionId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadPage(reset = true)
            }
        }
    }

    /** Stops loading for good. A deletion already on its way is still carried out; the scope the list was given is left running. */
    public fun close() {
        work.cancel()
    }

    private fun loadPage(reset: Boolean) {
        if (!work.isActive) return
        if (reset) {
            loadJob?.cancel()
            isLoadingPage = false
            hasMore = true
        }
        if (isLoadingPage || (!reset && !hasMore)) return
        isLoadingPage = true
        render()

        val offset = if (reset) 0 else chats.size
        loadJob = work.launch {
            try {
                val page = talqyn.consultant.chats(limit = pageSize, offset = offset)
                isLoadingPage = false
                hasMore = page.size == pageSize
                chats = if (reset) page else appending(page, chats)
                render()
            } catch (e: CancellationException) {
                // This job's own cancellation — a reload — leaves the state to the reload.
                ensureActive()
                // One from below ends the page like any failure: left alone, the list would stay
                // in its spinner for ever.
                fail(TalqynException.Transport(e))
            } catch (e: Exception) {
                fail(TalqynException.wrap(e))
            }
        }
    }

    private fun fail(error: TalqynException) {
        isLoadingPage = false
        if (chats.isEmpty()) {
            mutableState.value = State.Failed(error)
        } else {
            render()
        }
    }

    private fun render() {
        mutableState.value = when {
            chats.isEmpty() -> if (isLoadingPage) State.Loading else State.Empty
            else -> State.Loaded(chats, isLoadingPage)
        }
    }

    public companion object {
        private fun appending(page: List<TalqynChatSummary>, chats: List<TalqynChatSummary>): List<TalqynChatSummary> {
            val known = chats.mapTo(HashSet()) { it.sessionId }
            return chats + page.filter { it.sessionId !in known }
        }

        /**
         * The subtitle of a row: the time for today, "yesterday", otherwise the date, with the
         * year only when it is not this one.
         *
         * @param date When the last message was written.
         * @param strings For the word "yesterday".
         * @param locale The locale to write the date in — [TalqynUiStrings.locale] on the screen.
         * @param zone The time zone that decides what "today" is.
         * @param now The current moment; injectable for tests.
         */
        @JvmStatic
        public fun subtitle(
            date: Instant?,
            strings: TalqynUiStrings,
            locale: Locale = strings.locale,
            zone: ZoneId = ZoneId.systemDefault(),
            now: Instant = Instant.now(),
        ): String {
            if (date == null) return ""
            val day = date.atZone(zone).toLocalDate()
            val today = now.atZone(zone).toLocalDate()
            if (day == today) {
                return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(date.atZone(zone))
            }
            if (day == today.minusDays(1)) return strings.historyYesterday
            val formatter = if (day.year == today.year) {
                DateTimeFormatter.ofPattern(dayAndMonth(locale), locale)
            } else {
                DateTimeFormatter.ofPattern(mediumDateWithUnpaddedDay(locale), locale)
            }
            return formatter.format(LocalDate.from(day))
        }

        /**
         * The locale's day and month without the year: `d MMM` in Russian, `MMM d` in English.
         *
         * Derived from the medium pattern rather than written out, because the order is the
         * locale's: the twin SDK writes this date from the `dMMM` skeleton, and a fixed
         * `d MMM` would put an English month after its day. The span from the first of the
         * day and month fields to the last of them drops the year and whatever the locale
         * wraps it in — the Russian `'г'.`, the Kazakh `'ж'.` — without parsing them.
         */
        private fun dayAndMonth(locale: Locale): String {
            val pattern = mediumDateWithUnpaddedDay(locale)
            var start = -1
            var end = -1
            var isQuoted = false
            for ((index, char) in pattern.withIndex()) {
                if (char == '\'') {
                    isQuoted = !isQuoted
                } else if (!isQuoted && (char == 'd' || char == 'M')) {
                    if (start < 0) start = index
                    end = index
                }
            }
            return if (start < 0) "d MMM" else pattern.substring(start, end + 1)
        }

        /**
         * The locale's medium date with the day unpadded: `2025 ж. 5 қыр.`, not `2025 ж. 05 қыр.`.
         * The twin SDK writes the date from the `dMMMy` skeleton, which takes the order and the
         * words from the locale but pads no day; a medium pattern pads it in some locales.
         */
        private fun mediumDateWithUnpaddedDay(locale: Locale): String {
            val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.MEDIUM, null, IsoChronology.INSTANCE, locale)
            val out = StringBuilder(pattern.length)
            var isQuoted = false
            var index = 0
            while (index < pattern.length) {
                val char = pattern[index]
                if (char == '\'') {
                    isQuoted = !isQuoted
                } else if (!isQuoted && char == 'd' && pattern.getOrNull(index + 1) == 'd' && pattern.getOrNull(index + 2) != 'd') {
                    index += 1
                }
                out.append(char)
                index += 1
            }
            return out.toString()
        }
    }
}
