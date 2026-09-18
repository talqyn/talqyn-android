package com.talqyn.sdk

/**
 * One selectable value inside a facet group.
 *
 * @property slug The value to send back in `filters[<group slug>]`.
 * @property label The label to display, in the requested locale.
 * @property count How many products carry this value at the current selection.
 * @property state Whether the option is selected, selectable, or empty.
 * @property id For the `city` and `location` groups only: the place id **in your
 *   catalog's numbering** — the value to pass as [TalqynFilterCriteria.cityId] or
 *   [TalqynFilterCriteria.locationId] on the next request. Every other group filters
 *   through the structural [slug] and reports `null` here. A `null` on a `city` option
 *   happens in exactly one case: the selected city disappeared from the directory
 *   between requests. The option stays so the selection remains visible and removable,
 *   but there is nothing left to send it back with — clear the filter.
 * @property citySlug For the `location` group only: the slug of the store's city, to
 *   group stores underneath the options of the `city` group.
 */
public data class TalqynFilterOption(
    val slug: String,
    val label: String?,
    val count: Int,
    val state: State,
    val id: String?,
    val citySlug: String?,
) {
    /** Whether the option is currently applied. */
    public val isSelected: Boolean get() = state == State.Active

    /** Whether the option should be shown but not tappable. */
    public val isDisabled: Boolean get() = state == State.Disabled

    /**
     * Whether an option is selected, selectable, or empty at the current selection.
     *
     * An extensible wrapper rather than an enumeration: a value the SDK has not seen
     * must not break the whole filter panel.
     */
    @JvmInline
    public value class State(public val rawValue: String) {
        public companion object {
            /** Currently selected. */
            public val Active: State = State("active")

            /** Available to select. */
            public val Enabled: State = State("enabled")

            /** Zero products at the current selection. Do not hide it — render it inactive: options that vanish read as a filter that disappeared. */
            public val Disabled: State = State("disabled")
        }
    }

    internal companion object {
        fun decode(json: JsonObject): TalqynFilterOption = TalqynFilterOption(
            slug = json.string("slug") ?: "",
            label = json.string("label"),
            count = json.int("count") ?: 0,
            state = State(json.string("state") ?: State.Enabled.rawValue),
            id = json.string("id"),
            citySlug = json.string("city_slug"),
        )
    }
}

/**
 * One group of the filter panel.
 *
 * @property slug The group key. Also the key to use in `filters` on the next request.
 * @property label The group label to display, in the requested locale.
 * @property type How the group is meant to be rendered.
 * @property options The values in the group.
 * @property min The lower bound of a [Kind.Range] group.
 * @property max The upper bound of a [Kind.Range] group.
 * @property selectedMin The lower bound currently selected in a [Kind.Range] group.
 * @property selectedMax The upper bound currently selected in a [Kind.Range] group.
 */
public data class TalqynFilterGroup(
    val slug: String,
    val label: String?,
    val type: Kind,
    val options: List<TalqynFilterOption>,
    val min: Double?,
    val max: Double?,
    val selectedMin: Double?,
    val selectedMax: Double?,
) {
    /** The slugs currently selected — what to send back in `filters`. */
    public val selectedSlugs: List<String> get() = options.filter { it.isSelected }.map { it.slug }

    /** How a group is meant to be rendered. Extensible for the same reason as [TalqynFilterOption.State]. */
    @JvmInline
    public value class Kind(public val rawValue: String) {
        public companion object {
            /** A list of values. */
            public val List: Kind = Kind("list")

            /** A numeric range, bounded by [TalqynFilterGroup.min] and [TalqynFilterGroup.max]. */
            public val Range: Kind = Kind("range")

            /** A yes/no toggle. */
            public val Bool: Kind = Kind("bool")
        }
    }

    public companion object {
        /** The category group. */
        public const val CATEGORY_SLUG: String = "category"

        /** The brand group. */
        public const val BRAND_SLUG: String = "brand"

        /** The price range group. */
        public const val PRICE_SLUG: String = "price"

        /** The in-stock group. */
        public const val STOCK_SLUG: String = "stock"

        /** The discount group. */
        public const val DISCOUNT_SLUG: String = "discount"

        /** The city group. Its options carry [TalqynFilterOption.id]. */
        public const val CITY_SLUG: String = "city"

        /** The store group. Its options carry [TalqynFilterOption.id]. */
        public const val LOCATION_SLUG: String = "location"

        /** The former name of the store group, kept so a storefront that lived through the rename does not render stores twice. */
        public const val LEGACY_LOCATION_SLUG: String = "store"

        /**
         * The groups that select a place. They do not belong in the general filter
         * panel: a city and a store travel as the separate `cityId` and `locationId`
         * parameters, not as structural `filters`.
         */
        @JvmField
        public val placeSlugs: Set<String> = setOf(CITY_SLUG, LOCATION_SLUG, LEGACY_LOCATION_SLUG)

        internal fun decode(json: JsonObject): TalqynFilterGroup = TalqynFilterGroup(
            slug = json.string("slug") ?: "",
            label = json.string("label"),
            type = Kind(json.string("type") ?: Kind.List.rawValue),
            options = json.objects("options", TalqynFilterOption::decode),
            min = json.double("min"),
            max = json.double("max"),
            selectedMin = json.double("selected_min"),
            selectedMax = json.double("selected_max"),
        )
    }
}

/**
 * The result of `POST /v1/search/filters` — facet counts for the current query and the
 * filters already applied.
 *
 * @property groups Every group the server returned, in display order.
 */
public data class TalqynFiltersResponse(
    val groups: List<TalqynFilterGroup>,
) {
    /** Returns a group by slug, or `null` if the response has none with that slug. */
    public fun group(slug: String): TalqynFilterGroup? = groups.firstOrNull { it.slug == slug }

    /** The groups to render in the filter panel: everything except the city and store pickers. */
    public val panelGroups: List<TalqynFilterGroup>
        get() = groups.filter { it.slug !in TalqynFilterGroup.placeSlugs }

    /** The city directory. An option's [TalqynFilterOption.id] is what goes into [TalqynFilterCriteria.cityId]. */
    public val cityGroup: TalqynFilterGroup? get() = group(TalqynFilterGroup.CITY_SLUG)

    /**
     * The store directory, scoped to the selected city when there is one.
     *
     * May be absent entirely — for instance when a chain has one store per city and
     * "pick a store" would duplicate "pick a city". Absence is not an error: simply do
     * not render the picker.
     */
    public val locationGroup: TalqynFilterGroup?
        get() = group(TalqynFilterGroup.LOCATION_SLUG) ?: group(TalqynFilterGroup.LEGACY_LOCATION_SLUG)

    /** The price range group. */
    public val priceGroup: TalqynFilterGroup? get() = group(TalqynFilterGroup.PRICE_SLUG)

    /** Everything currently selected, shaped as the `filters` parameter of the next request. */
    public val selectedFilters: Map<String, List<String>>
        get() = panelGroups.mapNotNull { group -> group.selectedSlugs.takeIf { it.isNotEmpty() }?.let { group.slug to it } }.toMap()

    internal companion object {
        fun decode(json: JsonObject): TalqynFiltersResponse =
            TalqynFiltersResponse(json.objects("groups", TalqynFilterGroup::decode))
    }
}
