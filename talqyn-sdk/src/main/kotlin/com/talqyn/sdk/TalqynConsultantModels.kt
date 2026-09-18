package com.talqyn.sdk

/**
 * What the consultant is doing at this point in a turn.
 *
 * Extensible: a turn has more than one shape, and a new stage must not break stream
 * parsing in a shipped app.
 */
@JvmInline
public value class TalqynConsultantStage(public val rawValue: String) {
    public companion object {
        /** The turn has opened; the model is working out what was asked. */
        public val Thinking: TalqynConsultantStage = TalqynConsultantStage("thinking")

        /** The catalog is being searched. */
        public val Searching: TalqynConsultantStage = TalqynConsultantStage("searching")
    }
}

/**
 * Why a turn produced no text.
 *
 * The set is **open**: treat an unknown value as "no text, products are still there"
 * rather than as an error.
 */
@JvmInline
public value class TalqynFallbackReason(public val rawValue: String) {
    /**
     * Whether the reason is an exhausted budget. The two budget reasons call for
     * different wording to the shopper — one clears by itself, the other needs a plan
     * change — but both mean "not now".
     */
    public val isBudgetExhausted: Boolean get() = this == UserBudgetExceeded || this == BudgetExceeded

    public companion object {
        /**
         * This shopper — or this device, or this network address — has spent their
         * consultant budget for the next few hours.
         *
         * Under a device token this also covers the per-address ceiling shared by
         * everyone behind one NAT, so a shopper can hit it without a single extra turn of
         * their own. Tell them the consultant will be back later and show what was found;
         * search keeps working normally.
         */
        public val UserBudgetExceeded: TalqynFallbackReason = TalqynFallbackReason("user_budget_exceeded")

        /** The account's monthly budget is spent. The ceiling is raised on Talqyn's side. */
        public val BudgetExceeded: TalqynFallbackReason = TalqynFallbackReason("budget_exceeded")

        /** The token budget for this single turn ran out. */
        public val TurnBudget: TalqynFallbackReason = TalqynFallbackReason("turn_budget")

        /** The model did not answer in time. */
        public val Timeout: TalqynFallbackReason = TalqynFallbackReason("timeout")

        /** The model provider is circuit-broken after repeated failures. */
        public val CircuitOpen: TalqynFallbackReason = TalqynFallbackReason("circuit_open")

        /** The model declined to answer. */
        public val Refusal: TalqynFallbackReason = TalqynFallbackReason("refusal")
    }
}

/**
 * The products found during a turn.
 *
 * @property items Every product across all steps of the plan, flattened and deduplicated.
 * @property groups The same products split by role. Present only for a multi-step plan
 *   such as a bundle or a comparison.
 * @property searchId The impression id for this turn. Report clicks with
 *   [TalqynEventsApi.productClick] using this id and [TalqynEventSource.Consultant]. One id
 *   per turn, so a click's position is its index in the flattened [items].
 */
public data class TalqynConsultantProducts(
    val items: List<TalqynProduct>,
    val groups: List<TalqynProductGroup>? = null,
    val searchId: String? = null,
) {
    internal companion object {
        fun decode(json: JsonObject): TalqynConsultantProducts = TalqynConsultantProducts(
            items = json.objects("items", TalqynProduct::decode),
            groups = json.optionalObjects("groups", TalqynProductGroup::decode),
            searchId = json.string("search_id"),
        )
    }
}

/**
 * One step of a multi-step retrieval plan.
 *
 * @property role What this group is for, in the requested locale — "sofa", "matching table".
 * @property items The products found for this step.
 */
public data class TalqynProductGroup(
    val role: String,
    val items: List<TalqynProduct>,
) {
    internal companion object {
        fun decode(json: JsonObject): TalqynProductGroup = TalqynProductGroup(
            role = json.string("role") ?: "",
            items = json.objects("items", TalqynProduct::decode),
        )
    }
}

/**
 * A follow-up question asked before searching, when the request lacked context.
 *
 * There are no products in such a turn. Send the shopper's answer as an ordinary
 * [TalqynConsultantQuery.question] on the next request, carrying the same
 * [TalqynConsultantQuery.sessionId].
 *
 * @property message The lead-in to show above the questions.
 * @property questions The questions to render, usually as chips.
 */
public data class TalqynClarify(
    val message: String,
    val questions: List<Question>,
) {
    /**
     * One clarifying question.
     *
     * @property id The question id.
     * @property label The prompt to display.
     * @property multi Whether more than one option may be picked.
     * @property options The answers to offer.
     */
    public data class Question(
        val id: String,
        val label: String,
        val multi: Boolean = false,
        val options: List<String>,
    ) {
        internal companion object {
            fun decode(json: JsonObject): Question = Question(
                id = json.string("id") ?: "",
                label = json.string("label") ?: "",
                multi = json.bool("multi") ?: false,
                options = json.strings("options"),
            )
        }
    }

    internal companion object {
        fun decode(json: JsonObject): TalqynClarify = TalqynClarify(
            message = json.string("message") ?: "",
            questions = json.objects("questions", Question::decode),
        )
    }
}

/** An interface action the consultant proposes. */
public sealed interface TalqynConsultantAction {
    /** Apply a set of filters. The payload transfers into a listing request as-is — see [TalqynActionFilters.criteria]. */
    public data class ApplyFilters(val filters: TalqynActionFilters) : TalqynConsultantAction

    /** Show a comparison table. It is assembled by the server from catalog attributes, not invented by the model, so it is safe to render directly. */
    public data class ShowComparison(val table: TalqynComparisonTable) : TalqynConsultantAction

    /** An action this version of the SDK does not know, by the wire value of its type. */
    public data class Unknown(val type: String) : TalqynConsultantAction

    public companion object {
        internal fun decode(json: JsonObject): TalqynConsultantAction = when (val type = json.string("type") ?: "") {
            "apply_filters" -> ApplyFilters(json.obj("filters")?.let(TalqynActionFilters::decode) ?: TalqynActionFilters())
            "show_comparison" -> ShowComparison(json.obj("table")?.let(TalqynComparisonTable::decode) ?: TalqynComparisonTable())
            else -> Unknown(type)
        }
    }
}

/**
 * The filters carried by a [TalqynConsultantAction.ApplyFilters] action.
 *
 * The fields mirror a listing request, so the payload can be moved into one unchanged
 * once a query string is added.
 *
 * @property categoryId The category to narrow to.
 * @property brandId The brand to narrow to.
 * @property priceMin The lower price bound.
 * @property priceMax The upper price bound.
 * @property hasDiscount Whether to keep only discounted products.
 * @property filters Structural filters in the form `POST /v1/search/full` accepts.
 * @property attributes The same selection in the earlier flat form, one value per slug.
 *   Kept for compatibility only; a listing request ignores it, and it cannot express a
 *   multi-value selection. Send [filters].
 */
public data class TalqynActionFilters(
    val categoryId: Long? = null,
    val brandId: Long? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val hasDiscount: Boolean = false,
    val filters: Map<String, List<String>> = emptyMap(),
    val attributes: Map<String, String> = emptyMap(),
) {
    /**
     * Turns the payload into listing criteria.
     *
     * @param query The text to search for — usually the question that produced the
     *   action, or the query the conversation started from.
     * @param locale The language to search in. `null` uses the client default.
     * @param cityId The shopper's city, in your catalog's numbering.
     * @param locationId The shopper's store, in your catalog's numbering.
     */
    public fun criteria(
        query: String,
        locale: TalqynLocale? = null,
        cityId: String? = null,
        locationId: String? = null,
    ): TalqynFilterCriteria = TalqynFilterCriteria(
        query = query,
        locale = locale,
        categoryId = categoryId,
        brandId = brandId,
        priceMin = priceMin,
        priceMax = priceMax,
        hasDiscount = hasDiscount,
        filters = filters,
        cityId = cityId,
        locationId = locationId,
    )

    internal companion object {
        fun decode(json: JsonObject): TalqynActionFilters = TalqynActionFilters(
            categoryId = json.long("category_id"),
            brandId = json.long("brand_id"),
            priceMin = json.double("price_min"),
            priceMax = json.double("price_max"),
            hasDiscount = json.bool("has_discount") ?: false,
            filters = json.strictStringListMap("filters") ?: emptyMap(),
            attributes = json.strictStringMap("attrs") ?: emptyMap(),
        )
    }
}

/**
 * A product comparison table built by the server from catalog attributes.
 *
 * @property talqynIds The compared products, as Talqyn's internal ids. Resolve them
 *   against the turn's products to render cards; act in your own world through each
 *   card's [TalqynProduct.externalId].
 * @property titles The column headers, aligned with [talqynIds].
 * @property rows The comparison rows.
 */
public data class TalqynComparisonTable(
    val talqynIds: List<Long> = emptyList(),
    val titles: List<String> = emptyList(),
    val rows: List<Row> = emptyList(),
) {
    /**
     * One characteristic across every compared product.
     *
     * @property label The characteristic name.
     * @property values One value per column, aligned with [TalqynComparisonTable.talqynIds].
     *   `null` means the product does not have this characteristic.
     */
    public data class Row(
        val label: String,
        val values: List<String?>,
    ) {
        internal companion object {
            fun decode(json: JsonObject): Row = Row(
                label = json.string("label") ?: "",
                values = json.strictOptionalStrings("values") ?: emptyList(),
            )
        }
    }

    internal companion object {
        /** Ids and titles are aligned by index, so they decode as a unit; a row that does not decode is dropped on its own. */
        fun decode(json: JsonObject): TalqynComparisonTable = TalqynComparisonTable(
            talqynIds = json.strictLongs("talqyn_ids") ?: emptyList(),
            titles = json.strictStrings("titles") ?: emptyList(),
            rows = json.objects("rows", Row::decode),
        )
    }
}

/**
 * The end of a turn.
 *
 * @property sessionId The conversation id. Pass it on the next question to continue the dialogue.
 * @property turnId This turn's id: the key to rate it with [TalqynConsultantApi.submitFeedback].
 *   Every turn has one — a clarification and a fallback too, which is exactly where
 *   products, and a search id, are missing. `null` only from a server that predates ratings.
 * @property timeToFirstTokenMs Time to the first token of the answer, in milliseconds.
 *   `null` for turns that produced no streamed text.
 * @property totalMs How long the whole turn took, in milliseconds.
 */
public data class TalqynConsultantDone(
    val sessionId: String,
    val turnId: String? = null,
    val timeToFirstTokenMs: Int? = null,
    val totalMs: Int? = null,
) {
    internal companion object {
        fun decode(json: JsonObject): TalqynConsultantDone = TalqynConsultantDone(
            sessionId = json.string("session_id") ?: "",
            turnId = json.string("turn_id"),
            timeToFirstTokenMs = json.int("ttft_ms"),
            totalMs = json.int("total_ms"),
        )
    }
}

/**
 * A whole consultant turn delivered as one object, from [TalqynConsultantApi.answer].
 *
 * @property answer The answer text, with `[p:ID]` markers left in place — see
 *   [TalqynAnswerMarkup]. Empty on a clarify, redirect, or fallback turn.
 * @property sessionId The conversation id. Pass it on the next question to continue.
 * @property products The products found, flattened and deduplicated.
 * @property clarify The clarifying questions, when the turn asked for context instead of
 *   answering. [answer] and [products] are then empty.
 * @property groups The products split by role for a multi-step plan. `null` for a single search.
 * @property redirectQuery The query to run through ordinary search, when the consultant
 *   decided the request was a search rather than a consultation.
 * @property fallbackReason Why the turn produced no text, when it produced none.
 * @property actions The interface actions proposed by the turn.
 * @property followUps Ready-made follow-up prompts, to be sent verbatim as the next question.
 * @property searchId The impression id for the products of this turn.
 * @property turnId The turn's id, to rate it with [TalqynConsultantApi.submitFeedback].
 * @property tenantId The tenant the answer was produced for.
 */
public data class TalqynConsultantAnswer(
    val answer: String,
    val sessionId: String?,
    val products: List<TalqynProduct>,
    val clarify: TalqynClarify?,
    val groups: List<TalqynProductGroup>?,
    val redirectQuery: String?,
    val fallbackReason: TalqynFallbackReason?,
    val actions: List<TalqynConsultantAction>,
    val followUps: List<String>,
    val searchId: String?,
    val turnId: String?,
    val tenantId: String?,
) {
    /** Whether the turn degraded: products are present, text is not. */
    public val isFallback: Boolean get() = fallbackReason != null

    internal companion object {
        fun decode(json: JsonObject): TalqynConsultantAnswer = TalqynConsultantAnswer(
            answer = json.string("answer") ?: "",
            sessionId = json.string("session_id"),
            products = json.objects("products", TalqynProduct::decode),
            clarify = json.obj("clarify")?.let(TalqynClarify::decode),
            groups = json.optionalObjects("groups", TalqynProductGroup::decode),
            redirectQuery = json.string("redirect_query"),
            fallbackReason = json.string("fallback_reason")?.let(::TalqynFallbackReason),
            actions = json.objects("actions", TalqynConsultantAction::decode),
            followUps = json.strings("follow_ups"),
            searchId = json.string("search_id"),
            turnId = json.string("turn_id"),
            tenantId = json.string("tenant_id"),
        )
    }
}
