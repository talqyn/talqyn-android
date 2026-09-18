package com.talqyn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTests {
    private val light = TalqynTheme.Colors(
        accent = Color.Red, background = Color.White, surface = Color.White, surfaceSecondary = Color.LightGray,
        border = Color.Gray, textPrimary = Color.Black, textSecondary = Color.DarkGray, textTertiary = Color.Gray,
    )

    /** The shopper's bubble falls back to the accent, so a palette written before the role existed looks the same. */
    @Test
    fun bubbleDefaultsToTheAccent() {
        assertEquals(light.accent, light.bubble)
        assertEquals(light.onAccent, light.onBubble)
        val own = light.copy(bubble = Color.Blue, onBubble = Color.Yellow)
        assertEquals(Color.Blue, own.bubble)
        assertEquals(Color.Yellow, own.onBubble)
    }

    @Test
    fun theRolesAPaletteLeavesOutHaveWorkingDefaults() {
        assertEquals(Color.White, light.onAccent)
        assertNotEquals(Color.Unspecified, light.warning)
        assertNotEquals(Color.Unspecified, light.error)
        assertNotEquals(Color.Unspecified, light.rating)
        assertTrue("a shadow carries its strength in the alpha", light.shadow.alpha in 0.01f..0.5f)
    }

    /**
     * A card drawn on a surface as black as the screen vanishes into it, and so does a notice drawn
     * on a secondary surface the colour of the screen.
     */
    @Test
    fun theDefaultPalettesKeepTheirSurfacesApartFromTheScreen() {
        for (palette in listOf(TalqynTheme.Colors.Light, TalqynTheme.Colors.Dark)) {
            assertNotEquals(palette.background, palette.surface)
            assertNotEquals(palette.background, palette.surfaceSecondary)
            assertNotEquals(palette.surface, palette.surfaceSecondary)
        }
        assertNotEquals(TalqynTheme.Colors.Light.accent, TalqynTheme.Colors.Dark.accent)
    }

    /**
     * Which of the two palettes is drawn is decided in composition (`resolvedColors`); what the
     * theme itself says is that a `null` dark palette means one palette for every appearance.
     */
    @Test
    fun theDefaultThemeFollowsTheDeviceAndAPaletteCanStandAlone() {
        val theme = TalqynTheme()
        assertEquals(TalqynTheme.Appearance.System, theme.appearance)
        assertEquals(TalqynTheme.Colors.Light, theme.colors)
        assertEquals(TalqynTheme.Colors.Dark, theme.darkColors)
        assertEquals(TalqynTheme.Default, theme)
        assertNull(TalqynTheme(colors = light, darkColors = null).darkColors)
    }

    /** Every default icon is a vector that builds: a path that did not parse would throw here rather than on a shopper's screen. */
    @Test
    fun everyDefaultIconBuilds() {
        val icons = TalqynTheme.Icons.Default
        val all = listOf(
            icons.emptyState, icons.history, icons.newChat, icons.send, icons.stop, icons.scrollToBottom, icons.close,
            icons.back, icons.checkmark, icons.rateHelpful, icons.rateHelpfulOn, icons.rateNotHelpful, icons.rateNotHelpfulOn,
            icons.copyAnswer, icons.deleteChat, icons.filters, icons.comparison, icons.editQuestion, icons.imagePlaceholder,
            icons.ratingStar, icons.ratingStarFilled,
        )
        for (icon in all) {
            val vector = (icon as? TalqynIcon.Vector)?.image
            assertNotNull("a default role has no vector", vector)
            assertTrue("${vector?.name} draws nothing", (vector?.root?.size ?: 0) > 0)
            assertEquals(24f, vector?.viewportWidth)
        }
        assertEquals("the rated thumbs are their own art", false, icons.rateHelpful == icons.rateHelpfulOn)
    }

    /** The icon set is all defaults until an app replaces a role, and a role set to `null` draws nothing rather than falling back. */
    @Test
    fun iconsTakeReplacements() {
        val icons = TalqynTheme.Icons.Default.copy(newChat = TalqynIcon.Resource(42), emptyState = null)
        assertEquals(TalqynIcon.Resource(42), icons.newChat)
        assertNull(icons.emptyState)
        assertEquals("replacing one role must not disturb the others", TalqynTheme.Icons.Default.history, icons.history)
    }

    @Test
    fun theSystemTypeScaleCarriesTheDesignSizes() {
        val fonts = TalqynTheme.Fonts.System
        assertEquals(listOf(18, 16, 15, 15, 14, 13, 13, 12, 12, 11).map { it.sp }, fonts.all().map { it.fontSize })
        assertEquals(FontWeight.Bold, fonts.title.fontWeight)
        assertEquals(FontWeight.Normal, fonts.body.fontWeight)
        assertEquals(FontWeight.Bold, fonts.bodyBold.fontWeight)
        assertEquals(1.6f, fonts.maximumScale)
    }

    /** The app's own faces at the screen's sizes; the bold styles pick the bold face rather than thickening the regular one. */
    @Test
    fun customFontsUseTheAppsFacesWithoutFakeBold() {
        val fonts = TalqynTheme.Fonts.custom(regular = FontFamily.Serif, bold = FontFamily.Monospace)
        assertEquals(FontFamily.Serif, fonts.body.fontFamily)
        assertEquals(FontFamily.Serif, fonts.caption.fontFamily)
        assertEquals(FontFamily.Monospace, fonts.bodyBold.fontFamily)
        assertEquals(FontFamily.Monospace, fonts.title.fontFamily)
        assertEquals(FontWeight.Bold, fonts.label.fontWeight)
        assertTrue(fonts.all().all { it.fontSynthesis == FontSynthesis.None })
        assertEquals(TalqynTheme.Fonts.System.all().map { it.fontSize }, fonts.all().map { it.fontSize })

        val single = TalqynTheme.Fonts.custom(regular = FontFamily.Cursive)
        assertEquals("one family carrying both weights", FontFamily.Cursive, single.bodyBold.fontFamily)
    }

    /** The metrics are the screen's design until an app moves them. */
    @Test
    fun metricsCarryTheDesignAndAreReplaceable() {
        val metrics = TalqynTheme.Metrics()
        assertEquals(12.dp, metrics.cornerRadius)
        assertEquals(28.dp, metrics.composerRadius)
        assertEquals(36.dp, metrics.chipHeight)
        assertNull("a chip is a pill unless squared off", metrics.chipRadius)
        assertEquals(16.dp, metrics.horizontalMargin)
        assertEquals(84.dp, metrics.rowCardImageSize)
        assertEquals(175.dp, metrics.compactCardWidth)
        assertEquals(8.dp, metrics.cardRadius)
        assertEquals(16.dp, metrics.bubbleRadius)
        assertEquals(720.dp, metrics.maxContentWidth)

        val square = TalqynTheme(metrics = TalqynTheme.Metrics(cornerRadius = 4.dp, composerRadius = 8.dp, chipHeight = 28.dp, chipRadius = 4.dp))
        assertEquals(4.dp, square.metrics.cornerRadius)
        assertEquals(8.dp, square.metrics.composerRadius)
        assertEquals(28.dp, square.metrics.chipHeight)
        assertEquals(4.dp, square.metrics.chipRadius)
    }

    /** Haptics are on unless the app runs its own. */
    @Test
    fun hapticsCanBeTurnedOff() {
        assertTrue(TalqynTheme().hapticsEnabled)
        assertEquals(false, TalqynTheme(hapticsEnabled = false).hapticsEnabled)
    }

    private fun TalqynTheme.Fonts.all() =
        listOf(title, headline, body, bodyBold, callout, label, footnote, captionBold, caption, micro)
}
