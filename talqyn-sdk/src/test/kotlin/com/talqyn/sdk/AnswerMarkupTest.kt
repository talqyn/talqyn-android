package com.talqyn.sdk

import org.junit.Assert.assertEquals
import org.junit.Test
import com.talqyn.sdk.TalqynAnswerMarkup.Segment

class AnswerMarkupTest {
    @Test
    fun segmentsSplitTextAndProducts() {
        assertEquals(
            listOf(
                Segment.Text("Take "),
                Segment.Product(1234),
                Segment.Text(" — it is quieter than "),
                Segment.Product(99),
                Segment.Text("."),
            ),
            TalqynAnswerMarkup.segments("Take [p:1234] — it is quieter than [p:99]."),
        )
    }

    @Test
    fun textWithoutMarkersIsOneSegment() {
        assertEquals(listOf(Segment.Text("plain text")), TalqynAnswerMarkup.segments("plain text"))
        assertEquals(emptyList<Segment>(), TalqynAnswerMarkup.segments(""))
    }

    @Test
    fun markerAtBothEnds() {
        assertEquals(
            listOf(Segment.Product(1), Segment.Text(" and "), Segment.Product(2)),
            TalqynAnswerMarkup.segments("[p:1] and [p:2]"),
        )
    }

    @Test
    fun strippedRemovesMarkers() {
        assertEquals("Take .", TalqynAnswerMarkup.stripped("Take [p:1234]."))
    }

    @Test
    fun mentionedIdsInOrder() {
        assertEquals(listOf(3L, 1L, 3L), TalqynAnswerMarkup.mentionedProductIds("[p:3] [p:1] [p:3]"))
    }

    @Test
    fun malformedMarkerStaysText() {
        assertEquals(listOf(Segment.Text("[p:abc]")), TalqynAnswerMarkup.segments("[p:abc]"))
    }

    /** A number too large for a `Long` looks like a marker and stays text. */
    @Test
    fun overflowingMarkerStaysText() {
        assertEquals(listOf(Segment.Text("a [p:99999999999999999999] b")), TalqynAnswerMarkup.segments("a [p:99999999999999999999] b"))
    }

    /** Ids are 64-bit, as on the server: one past 2³¹ is a product like any other, not text. */
    @Test
    fun idBeyondIntIsAProduct() {
        assertEquals(listOf(Segment.Product(99999999999)), TalqynAnswerMarkup.segments("[p:99999999999]"))
    }
}
