package com.talqyn.sample

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import com.talqyn.sdk.TalqynHttpRequest
import com.talqyn.sdk.TalqynHttpResponse
import com.talqyn.sdk.TalqynHttpResult
import com.talqyn.sdk.TalqynHttpStream
import com.talqyn.sdk.TalqynHttpTransport
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID

/**
 * A transport that plays Talqyn's part: a device token, scripted consultant turns streamed at a
 * reading pace, a chat history, and a quiet `204` for ratings and events.
 *
 * The turn follows the question, so every state of the screen can be reached without a server:
 * - "compare" compares two phones, "compare the first two" — the first two dishwashers;
 * - "gift" asks a clarifying question;
 * - "limit" degrades with products;
 * - "iphone" is a search query, and the consultant redirects it to search;
 * - "error" fails on Talqyn's side, with an `error` event;
 * - "drop" is cut off before its `done`, like a dropped connection;
 * - "often" meets an empty consultant bucket, `429`;
 * - "rating" answers, and Talqyn refuses to save a rating of that answer;
 * - anything else answers about dishwashers.
 *
 * In history, "Deleted chat" is gone by the time it is opened: `404`.
 */
internal class DemoTransport : TalqynHttpTransport {
    /** Turns whose ratings Talqyn "refuses", so the thumb goes back and the screen says why. */
    private val refusedTurns: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult {
        delay(300)
        val path = URI(request.url).path
        return when {
            path.endsWith("/consultant/token") -> json(
                JSONObject()
                    .put("token", "tlqd_demo")
                    .put("expires_at", Instant.now().plusSeconds(900).toString())
                    .put("expires_in", 900)
                    .apply { requestJson(request)?.optString("user_id")?.takeIf { it.isNotEmpty() }?.let { put("user_id", it) } },
            )
            path.contains("/consultant/feedback") -> {
                val turn = requestJson(request)?.optString("turn_id")?.takeIf { it.isNotEmpty() } ?: path.substringAfterLast('/')
                if (turn in refusedTurns) {
                    json(JSONObject().put("error", "internal_error").put("request_id", "req_demo"), status = 500)
                } else {
                    empty()
                }
            }
            request.method == "GET" && path.endsWith("/consultant/chats") -> json(chats())
            request.method == "GET" && path.contains("/consultant/chats/") ->
                transcript(path.substringAfterLast('/'))?.let { json(it) }
                    ?: json(JSONObject().put("detail", "chat not found"), status = 404)
            else -> empty()
        }
    }

    override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream {
        val json = requestJson(request)
        val question = json?.optString("question").orEmpty().lowercase()
        if ("often" in question) {
            delay(300)
            // A wait longer than the retry policy's ceiling: the SDK hands the refusal over at once.
            return TalqynHttpStream(TalqynHttpResponse(429, mapOf("Retry-After" to "30")), flowOf("""{"detail":"Rate limit exceeded"}"""))
        }
        val session = json?.optString("session_id")?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val steps = script(question, session)
        val lines = flow {
            for (step in steps) {
                delay(step.delayMillis)
                emit("event: ${step.name}")
                emit("data: ${step.data}")
                emit("")
            }
        }
        return TalqynHttpStream(TalqynHttpResponse(200), lines)
    }

    private class Step(val name: String, val data: String, val delayMillis: Long)

    private fun script(question: String, session: String): List<Step> {
        val steps = ArrayList<Step>()
        fun event(name: String, data: JSONObject, delayMillis: Long = 400) = steps.add(Step(name, data.toString(), delayMillis))
        fun text(answer: String) = answer.chunked(7).forEach { event("delta", JSONObject().put("text", it), 35) }
        fun done(refusesRating: Boolean = false) {
            val turn = UUID.randomUUID().toString()
            if (refusesRating) refusedTurns.add(turn)
            event("done", JSONObject().put("session_id", session).put("turn_id", turn).put("total_ms", 4200))
        }

        event("status", JSONObject().put("stage", "thinking"), 200)
        when {
            "gift" in question -> {
                event(
                    "clarify",
                    JSONObject()
                        .put("message", "Tell me a couple of details and I will narrow the gift down")
                        .put(
                            "questions",
                            JSONArray()
                                .put(question("budget", "What is the budget?", multi = false, "under 20 000 ₸", "20–50k ₸", "over 50k ₸"))
                                .put(question("room", "Which room is it for?", multi = true, "Kitchen", "Living room", "Bedroom")),
                        ),
                    900,
                )
                done()
            }
            "limit" in question -> {
                event("status", JSONObject().put("stage", "searching"))
                event("products", JSONObject().put("items", JSONArray().put(dishwasher(101)).put(dishwasher(102))).put("search_id", "srch_demo"))
                event("fallback", JSONObject().put("reason", "user_budget_exceeded"))
                done()
            }
            "iphone" in question -> {
                event("redirect_to_search", JSONObject().put("query", "iPhone 16"), 600)
                done()
            }
            "error" in question -> {
                event("status", JSONObject().put("stage", "searching"), 600)
                event("error", JSONObject().put("code", "retrieval_failed"))
                done()
            }
            "drop" in question -> {
                event("status", JSONObject().put("stage", "searching"))
                event("products", JSONObject().put("items", JSONArray().put(dishwasher(101)).put(dishwasher(103))).put("search_id", "srch_demo"))
                text("Here is how the two compare, but first things first: ")
                // No `done`: the stream just ends, as a connection dropped by a proxy does.
            }
            "compar" in question && ("first" in question || "dishwash" in question) -> {
                event("status", JSONObject().put("stage", "searching"))
                event("products", JSONObject().put("items", JSONArray().put(dishwasher(101)).put(dishwasher(102))).put("search_id", "srch_demo"))
                text("Both are quiet, but [p:101] is 2 dB quieter and has a Zeolith dryer, while [p:102] is 20 000 ₸ cheaper.")
                comparison(
                    ids = listOf(101, 102),
                    titles = listOf("Bosch", "Electrolux"),
                    rows = listOf(
                        row("Price", "239990", "219990"),
                        row("Brand", "Bosch", "Electrolux"),
                        row("Noise level", "42 dB", "44 dB"),
                        row("Width", "60 cm", "60 cm"),
                    ),
                    event = ::event,
                )
                done()
            }
            "compar" in question -> {
                event("status", JSONObject().put("stage", "searching"))
                event("products", JSONObject().put("items", JSONArray().put(phone(201)).put(phone(202))).put("search_id", "srch_demo"))
                text(
                    "Both phones are flagships, but for different things. [p:201] and [p:202] are close in price.\n\n" +
                        "**Camera.** Samsung has three modules and a 3x zoom, Apple the better video stabilization.\n\n" +
                        "- Battery: Samsung lasts longer\n- Ecosystem: Apple, if you have a Mac or the watch",
                )
                comparison(
                    ids = listOf(201, 202),
                    titles = listOf("Galaxy S25", "iPhone 16"),
                    rows = listOf(
                        row("Price", "449990", "529990"),
                        row("Brand", "Samsung", "Apple"),
                        row("Diagonal", "6.2\"", "6.1\""),
                        row("Storage", "256 GB", "256 GB"),
                        row("Battery", "4000 mAh", "3561 mAh"),
                    ),
                    event = ::event,
                )
                event("follow_ups", JSONObject().put("items", JSONArray().put("What about the camera at night?").put("Is there a cheaper one?")))
                done()
            }
            else -> {
                event("status", JSONObject().put("stage", "searching"), 700)
                val items = JSONArray().put(dishwasher(101)).put(dishwasher(102)).put(dishwasher(103)).put(dishwasher(104))
                event("products", JSONObject().put("items", items).put("search_id", "srch_demo"), 900)
                text(
                    "Picked the quiet models under 250 000 ₸.\n\n" +
                        "The quietest one is [p:101]: 42 dB and a Zeolith dryer, you barely hear it at night.\n\n" +
                        "What matters in the choice:\n" +
                        "- **Noise level** up to 44 dB\n" +
                        "- Energy class A++\n" +
                        "- Leak protection\n\n" +
                        "If you need a narrow 45 cm one, look at [p:103].",
                )
                event(
                    "action",
                    JSONObject().put("type", "apply_filters").put(
                        "filters",
                        JSONObject().put("price_max", 250000).put("filters", JSONObject().put("brand", JSONArray().put("Bosch").put("Electrolux"))),
                    ),
                )
                event("follow_ups", JSONObject().put("items", JSONArray().put("Is there one with a dryer?").put("Which one is the quietest?").put("Compare the first two")))
                done(refusesRating = "rating" in question)
            }
        }
        return steps
    }

    private fun comparison(
        ids: List<Int>,
        titles: List<String>,
        rows: List<JSONObject>,
        event: (String, JSONObject, Long) -> Boolean,
    ) {
        val table = JSONObject()
            .put("talqyn_ids", JSONArray(ids))
            .put("titles", JSONArray(titles))
            .put("rows", JSONArray(rows))
        event("action", JSONObject().put("type", "show_comparison").put("table", table), 400)
    }

    private fun question(id: String, label: String, multi: Boolean, vararg options: String) =
        JSONObject().put("id", id).put("label", label).put("multi", multi).put("options", JSONArray(options.toList()))

    private fun row(label: String, vararg values: String) = JSONObject().put("label", label).put("values", JSONArray(values.toList()))

    private fun dishwasher(id: Int): JSONObject = when (id) {
        101 -> product(id, "Bosch SMS4HVI33E dishwasher", "Bosch", 239990.0, 279990.0, 4.8, 126)
        102 -> product(id, "Electrolux EEM48321L dishwasher", "Electrolux", 219990.0, null, 4.6, 58)
        103 -> product(id, "Weissgauff BDW 4543 D dishwasher", "Weissgauff", 149990.0, 169990.0, 4.5, 211)
        else -> product(id, "Midea MFD60S970Wi dishwasher", "Midea", 189990.0, null, 0.0, 0, inStock = false)
    }

    private fun phone(id: Int): JSONObject = when (id) {
        201 -> product(id, "Samsung Galaxy S25 256GB", "Samsung", 449990.0, 499990.0, 4.9, 342)
        else -> product(id, "Apple iPhone 16 256GB", "Apple", 529990.0, null, 4.8, 518)
    }

    private fun product(
        id: Int,
        title: String,
        brand: String,
        price: Double,
        priceBefore: Double?,
        rating: Double,
        reviews: Int,
        inStock: Boolean = true,
    ): JSONObject = JSONObject()
        .put("talqyn_id", id)
        .put("external_id", "SKU-$id")
        .put("title", title)
        .put("brand_name", brand)
        .put("price", price)
        .put("price_before", priceBefore ?: JSONObject.NULL)
        .put("rating", rating)
        .put("reviews_count", reviews)
        .put("in_stock", inStock)
        .put("image_url", "https://picsum.photos/seed/talqyn$id/400/400")

    private fun chats(): JSONArray {
        val now = Instant.now()
        return JSONArray()
            .put(chat("s-1", "A quiet dishwasher under 250 000 ₸", now.minus(2, ChronoUnit.HOURS)))
            .put(chat("s-2", "Compare two smartphones", now.minus(1, ChronoUnit.DAYS)))
            .put(chat("s-3", "What to give as a housewarming gift?", now.minus(9, ChronoUnit.DAYS)))
            .put(chat("s-gone", "Deleted chat", now.minus(400, ChronoUnit.DAYS)))
    }

    private fun chat(id: String, title: String, at: Instant) = JSONObject()
        .put("session_id", id)
        .put("title", title)
        .put("message_count", 2)
        .put("created_at", at.toString())
        .put("last_message_at", at.toString())

    /** The transcript of a chat from [chats], or `null` for one that was deleted meanwhile. */
    private fun transcript(sessionId: String): JSONObject? = when (sessionId) {
        "s-1" -> transcript(
            sessionId,
            question = "A quiet dishwasher under 250 000 ₸",
            answer = "The quietest one is [p:101]: 42 dB and a Zeolith dryer.",
            products = listOf(dishwasher(101), dishwasher(102)),
            feedback = "up",
        )
        "s-2" -> transcript(
            sessionId,
            question = "Compare two smartphones",
            answer = "Both are flagships: [p:201] with the zoom, [p:202] with the video stabilization.",
            products = listOf(phone(201), phone(202)),
        )
        "s-3" -> transcript(
            sessionId,
            question = "What to give as a housewarming gift?",
            answer = "Tell me the budget and the room — I will narrow the gift down.",
            products = emptyList(),
        )
        else -> null
    }

    private fun transcript(sessionId: String, question: String, answer: String, products: List<JSONObject>, feedback: String? = null): JSONObject {
        val turn = UUID.nameUUIDFromBytes(sessionId.toByteArray()).toString()
        val ids = JSONArray(products.map { it.getInt("talqyn_id") })
        return JSONObject()
            .put("session_id", sessionId)
            .put("title", question)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("text", question).put("turn_id", turn).put("route", "consult"))
                    .put(
                        JSONObject().put("role", "assistant").put("text", answer).put("talqyn_ids", ids).put("turn_id", turn)
                            .apply { feedback?.let { put("feedback", it) } },
                    ),
            )
            .put("products", JSONArray(products))
    }

    private fun json(value: Any, status: Int = 200) = TalqynHttpResult(value.toString().toByteArray(), TalqynHttpResponse(status))

    private fun empty() = TalqynHttpResult(ByteArray(0), TalqynHttpResponse(204))

    private fun requestJson(request: TalqynHttpRequest): JSONObject? =
        request.body?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
}
