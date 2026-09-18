package com.talqyn.consultant

import com.talqyn.sdk.TalqynConsultantStage
import com.talqyn.sdk.TalqynFallbackReason

/**
 * Products are in, the first token of the text is not. Not a server stage: derived on
 * the client so the status line has something to say between the two.
 */
public val TalqynConsultantStage.Companion.Composing: TalqynConsultantStage
    get() = TalqynConsultantStage("composing")

/**
 * The reason without its detail: the wire value may carry a suffix after a `:`, and
 * copy and decisions key off what comes before it.
 */
public val TalqynFallbackReason.base: TalqynFallbackReason
    get() = rawValue.indexOf(':').let { colon -> if (colon < 0) this else TalqynFallbackReason(rawValue.substring(0, colon)) }

/**
 * Whether asking the same question again could plausibly work.
 *
 * Not after a spent budget — the shopper's or the account's — nor after a turn that ran
 * out of its own budget or that the model refused: the repeat would meet the same wall.
 * A timeout or an open circuit may clear, and an unknown reason is given the benefit of
 * the doubt.
 */
public val TalqynFallbackReason.invitesRetry: Boolean
    get() {
        val base = base
        return !(base.isBudgetExhausted || base == TalqynFallbackReason.TurnBudget || base == TalqynFallbackReason.Refusal)
    }
