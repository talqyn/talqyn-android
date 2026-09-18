package com.talqyn.sdk

/**
 * Request defaults: locale, the shopper's place, the A/B bucket.
 *
 * They change at runtime, so they live in one guarded place instead of being copied
 * into every API surface. Every `apply` reads one [Snapshot]; a caller that sends two
 * requests about one selection passes the same snapshot to both, so a place changed in
 * between cannot land in one request and not the other.
 */
internal class TalqynDefaults(configuration: TalqynConfiguration) {
    data class Snapshot(
        val locale: TalqynLocale,
        val cityId: String?,
        val locationId: String?,
        val variant: String?,
    )

    private val lock = Any()
    private var snapshot = Snapshot(
        locale = configuration.defaultLocale,
        cityId = configuration.defaultCityId,
        locationId = configuration.defaultLocationId,
        variant = configuration.variant,
    )

    val current: Snapshot get() = synchronized(lock) { snapshot }

    fun update(change: (Snapshot) -> Snapshot) {
        synchronized(lock) { snapshot = change(snapshot) }
    }

    // Place is applied as a unit or not at all. A store beats a city, so adding a
    // default store to an explicitly named city would silently override the
    // caller's choice.

    fun apply(query: TalqynSearchQuery, defaults: Snapshot = current): TalqynSearchQuery {
        val namesPlace = query.cityId != null || query.locationId != null
        return query.copy(
            locale = query.locale ?: defaults.locale,
            variant = query.variant ?: defaults.variant,
            cityId = if (namesPlace) query.cityId else defaults.cityId,
            locationId = if (namesPlace) query.locationId else defaults.locationId,
        )
    }

    fun apply(criteria: TalqynFilterCriteria, defaults: Snapshot = current): TalqynFilterCriteria {
        val namesPlace = criteria.cityId != null || criteria.locationId != null
        return criteria.copy(
            locale = criteria.locale ?: defaults.locale,
            cityId = if (namesPlace) criteria.cityId else defaults.cityId,
            locationId = if (namesPlace) criteria.locationId else defaults.locationId,
        )
    }

    fun apply(query: TalqynFullSearchQuery, defaults: Snapshot = current): TalqynFullSearchQuery =
        query.copy(criteria = apply(query.criteria, defaults), variant = query.variant ?: defaults.variant)

    fun apply(query: TalqynFiltersQuery, defaults: Snapshot = current): TalqynFiltersQuery =
        query.copy(criteria = apply(query.criteria, defaults))

    fun apply(query: TalqynConsultantQuery, defaults: Snapshot = current): TalqynConsultantQuery {
        val namesPlace = query.cityId != null || query.locationId != null
        return query.copy(
            locale = query.locale ?: defaults.locale,
            variant = query.variant ?: defaults.variant,
            cityId = if (namesPlace) query.cityId else defaults.cityId,
            locationId = if (namesPlace) query.locationId else defaults.locationId,
        )
    }

    fun apply(event: TalqynSearchSubmitEvent, defaults: Snapshot = current): TalqynSearchSubmitEvent =
        event.copy(locale = event.locale ?: defaults.locale, variant = event.variant ?: defaults.variant)

    fun apply(event: TalqynProductClickEvent, defaults: Snapshot = current): TalqynProductClickEvent =
        event.copy(variant = event.variant ?: defaults.variant)

    fun apply(event: TalqynCategoryClickEvent, defaults: Snapshot = current): TalqynCategoryClickEvent =
        event.copy(variant = event.variant ?: defaults.variant)

    fun apply(feedback: TalqynFeedback, defaults: Snapshot = current): TalqynFeedback =
        feedback.copy(variant = feedback.variant ?: defaults.variant)
}
