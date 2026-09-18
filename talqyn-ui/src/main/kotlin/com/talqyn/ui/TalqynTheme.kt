package com.talqyn.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the consultant screens look like: the client's colors, type, icons, and shapes.
 *
 * The SDK draws every element itself; the app registers the palette and the type once and
 * every screen follows it.
 *
 * ```kotlin
 * val theme = TalqynTheme(
 *     colors = TalqynTheme.Colors(accent = Brand, background = Grey03, ...),
 *     darkColors = TalqynTheme.Colors(accent = BrandLifted, background = Ink, ...),
 *     fonts = TalqynTheme.Fonts.custom(regular = MuseoSans500, bold = MuseoSans700),
 * )
 * ```
 *
 * @property colors The palette for a light appearance — or for every appearance, when
 *   [darkColors] is `null`.
 * @property darkColors The palette for a dark appearance. `null` draws [colors] whatever the
 *   device is set to.
 * @property fonts The type scale.
 * @property icons The icons.
 * @property metrics The shapes and margins.
 * @property appearance Light, dark, or whatever the device says.
 * @property hapticsEnabled Whether a tap on a chip, a card, or the send button answers with a
 *   light knock. Off for an app that runs its own haptics policy — or none.
 */
@Immutable
public data class TalqynTheme(
    val colors: Colors = Colors.Light,
    val darkColors: Colors? = Colors.Dark,
    val fonts: Fonts = Fonts.System,
    val icons: Icons = Icons.Default,
    val metrics: Metrics = Metrics(),
    val appearance: Appearance = Appearance.System,
    val hapticsEnabled: Boolean = true,
) {
    /**
     * The palette. Named by role, not by shade, so an app maps its own tokens onto it without
     * guessing what "grey 65" is for.
     *
     * @property accent Buttons, links, the shopper's bubble, the active state of a chip.
     * @property onAccent Text on [accent].
     * @property background The screen background.
     * @property surface Cards, the composer, the header.
     * @property surfaceSecondary Notices, chips, the clarify card — one step off the surface.
     * @property border Dividers and chip outlines.
     * @property textPrimary Body text, prices, titles.
     * @property textSecondary Secondary text: status lines, labels, product titles.
     * @property textTertiary Placeholders, struck-through prices, disabled controls.
     * @property bubble The shopper's own message. Defaults to [accent]; set it when the brand's
     *   bubble is not the brand's button.
     * @property onBubble Text on [bubble]. Defaults to [onAccent].
     * @property warning A turn that degraded: products are there, the text is not.
     * @property error A turn that failed.
     * @property rating The stars of a product's rating. Its own role rather than [warning]: a brand
     *   whose warning is orange does not necessarily want orange stars.
     * @property shadow What falls under the composer and the scroll-to-bottom button. Carry the
     *   strength in the alpha; [Color.Transparent] removes shadows altogether.
     */
    @Immutable
    public data class Colors(
        val accent: Color,
        val onAccent: Color = Color.White,
        val background: Color,
        val surface: Color,
        val surfaceSecondary: Color,
        val border: Color,
        val textPrimary: Color,
        val textSecondary: Color,
        val textTertiary: Color,
        val bubble: Color = accent,
        val onBubble: Color = onAccent,
        val warning: Color = Color(0xFFFFCC00),
        val error: Color = Color(0xFFFF3B30),
        val rating: Color = Color(0xFFFFCC00),
        val shadow: Color = Color.Black.copy(alpha = 0.12f),
    ) {
        public companion object {
            /** A neutral light palette that looks like nobody's brand, which is the point of a default. */
            @JvmField
            public val Light: Colors = Colors(
                accent = Color(0xFF007AFF),
                background = Color(0xFFF2F2F7),
                surface = Color.White,
                // A step off both the surface and the background: a notice or an action chip sits
                // on the screen itself, and must not vanish into it.
                surfaceSecondary = Color(0xFFE9E9EF),
                border = Color(0x4A3C3C43),
                textPrimary = Color.Black,
                textSecondary = Color(0x993C3C43),
                textTertiary = Color(0x4D3C3C43),
            )

            /**
             * The dark twin of [Light]. The surfaces stay apart from the background: cards drawn
             * on a surface as black as the screen vanish into it.
             */
            @JvmField
            public val Dark: Colors = Colors(
                accent = Color(0xFF0A84FF),
                background = Color.Black,
                surface = Color(0xFF1C1C1E),
                surfaceSecondary = Color(0xFF2C2C2E),
                border = Color(0xA6545458),
                textPrimary = Color.White,
                textSecondary = Color(0x99EBEBF5),
                textTertiary = Color(0x4DEBEBF5),
                warning = Color(0xFFFFD60A),
                error = Color(0xFFFF453A),
                rating = Color(0xFFFFD60A),
                shadow = Color.Black.copy(alpha = 0.5f),
            )
        }
    }

    /**
     * The type scale. Sizes follow the original design of the screen; an app supplies its
     * family through [custom] or picks every style by hand.
     *
     * The text follows the system font size, up to [maximumScale] times its design size: cards
     * and chips stay laid out as designed while text is markedly larger. Set [maximumScale] to 1
     * for styles the app has already scaled itself.
     *
     * @property title The empty-state title. 18, bold.
     * @property headline The header title and answer headings. 16, bold.
     * @property body Answer text, bubbles, the composer. 15, regular.
     * @property bodyBold Emphasis inside answer text. 15, bold.
     * @property callout Product titles in a row card, the clarify message. 14, regular.
     * @property label Labels and small buttons. 13, bold.
     * @property footnote Notices, secondary text. 13, regular.
     * @property captionBold Section headers, ratings, comparison cells. 12, bold.
     * @property caption Compact product titles, reviews count. 12, regular.
     * @property micro The character counter and the disclaimer. 11, regular.
     */
    @Immutable
    public data class Fonts(
        val title: TextStyle,
        val headline: TextStyle,
        val body: TextStyle,
        val bodyBold: TextStyle,
        val callout: TextStyle,
        val label: TextStyle,
        val footnote: TextStyle,
        val captionBold: TextStyle,
        val caption: TextStyle,
        val micro: TextStyle,
        val maximumScale: Float = 1.6f,
    ) {
        public companion object {
            /** The system font at the screen's sizes. */
            @JvmField
            public val System: Fonts = scale(FontFamily.Default, FontFamily.Default, synthesis = FontSynthesis.All)

            /**
             * The screen's sizes on the app's own typefaces.
             *
             * Declare the bold family's font with `FontWeight.Bold` — `Font(R.font.brand_bold,
             * FontWeight.Bold)` — so the bold styles pick it rather than thickening the regular one.
             *
             * @param regular The regular family.
             * @param bold The bold family. Defaults to [regular], for a family that carries both weights.
             */
            @JvmStatic
            public fun custom(regular: FontFamily, bold: FontFamily = regular): Fonts =
                scale(regular, bold, synthesis = FontSynthesis.None)

            private fun scale(regular: FontFamily, bold: FontFamily, synthesis: FontSynthesis): Fonts {
                fun style(family: FontFamily, size: Int, weight: FontWeight) =
                    TextStyle(fontFamily = family, fontSize = size.sp, fontWeight = weight, fontSynthesis = synthesis)
                return Fonts(
                    title = style(bold, 18, FontWeight.Bold),
                    headline = style(bold, 16, FontWeight.Bold),
                    body = style(regular, 15, FontWeight.Normal),
                    bodyBold = style(bold, 15, FontWeight.Bold),
                    callout = style(regular, 14, FontWeight.Normal),
                    label = style(bold, 13, FontWeight.Bold),
                    footnote = style(regular, 13, FontWeight.Normal),
                    captionBold = style(bold, 12, FontWeight.Bold),
                    caption = style(regular, 12, FontWeight.Normal),
                    micro = style(regular, 11, FontWeight.Normal),
                )
            }
        }
    }

    /**
     * The icons the screens draw. Named by role, like the palette.
     *
     * The defaults are the SDK's own vectors; an app with its own icon set passes its own and
     * the screens stop looking like the only screen drawn by somebody else. Icons are tinted
     * with the role's color. A `null` role draws nothing — an empty screen without its sparkle,
     * a button with only its label.
     *
     * ```kotlin
     * val icons = TalqynTheme.Icons.Default.copy(newChat = TalqynIcon.Resource(R.drawable.pds_edit))
     * ```
     */
    @Immutable
    public data class Icons(
        /** Over the title on an empty screen. */
        val emptyState: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Sparkles),
        /** Opens the chat history. */
        val history: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.History),
        /** Starts a new chat. */
        val newChat: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.NewChat),
        /** Sends the question. */
        val send: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Send),
        /** Stops an answer while it streams. */
        val stop: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Stop),
        /** Jumps to the end of the transcript. */
        val scrollToBottom: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.ArrowDown),
        /** Closes a screen the SDK opened — and the consultant itself, with [TalqynNavigation.Close]. */
        val close: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Close),
        /** Goes back from the consultant, with [TalqynNavigation.Back]. */
        val back: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Back),
        /** Confirms a copy. */
        val checkmark: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Check),
        /** Rates an answer up. */
        val rateHelpful: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.ThumbUp),
        /** The same, once the shopper has. Custom art needs its own twin, or it repeats the unrated one and the tint does the talking. */
        val rateHelpfulOn: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.ThumbUpFilled),
        /** Rates an answer down. */
        val rateNotHelpful: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.ThumbDown),
        /** The same, once the shopper has. */
        val rateNotHelpfulOn: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.ThumbDownFilled),
        /** Copies an answer. */
        val copyAnswer: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Copy),
        /** Deletes a conversation from the history. */
        val deleteChat: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Delete),
        /** The chip that opens a filtered listing. */
        val filters: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Search),
        /** The chip that opens the comparison table. */
        val comparison: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Compare),
        /** Puts the shopper's own question back into the composer. */
        val editQuestion: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Edit),
        /** Stands where a product image is missing or still on its way. */
        val imagePlaceholder: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Image),
        /** An empty star of a product's rating. */
        val ratingStar: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.StarOutline),
        /** A filled star of a product's rating. */
        val ratingStarFilled: TalqynIcon? = TalqynIcon.Vector(TalqynVectors.Star),
    ) {
        public companion object {
            /** The icons the screens were designed on. */
            @JvmField
            public val Default: Icons = Icons()
        }
    }

    /**
     * The shapes and the rhythm: what is round by how much, and how wide the margins are.
     *
     * The sizes are the screen's design; an app whose language is squarer — or rounder — moves
     * them all from one place rather than living with a consultant that rounds differently from
     * the rest of it.
     *
     * @property cornerRadius Cards, notices, the clarify card.
     * @property composerRadius The composer's pill; drop it for a squarer input.
     * @property chipHeight A chip's height.
     * @property chipRadius A chip's radius. `null` keeps it a pill, whatever its height.
     * @property horizontalMargin The margin down both sides of the transcript: text, carousel,
     *   bubble, and composer line up on it.
     * @property rowCardImageSize The image of a card that stands at the full width.
     * @property compactCardWidth The width of a tile in the carousel at the design text size; it
     *   grows with the type.
     * @property cardRadius Product cards and the images inside them.
     * @property bubbleRadius The shopper's message bubble; its bottom-end corner stays square.
     * @property maxContentWidth The widest the conversation column grows. On a phone the screen is
     *   narrower and this changes nothing; on a tablet a line of the answer would otherwise run
     *   the width of the display.
     */
    @Immutable
    public data class Metrics(
        val cornerRadius: Dp = 12.dp,
        val composerRadius: Dp = 28.dp,
        val chipHeight: Dp = 36.dp,
        val chipRadius: Dp? = null,
        val horizontalMargin: Dp = 16.dp,
        val rowCardImageSize: Dp = 84.dp,
        val compactCardWidth: Dp = 175.dp,
        val cardRadius: Dp = 8.dp,
        val bubbleRadius: Dp = 16.dp,
        val maxContentWidth: Dp = 720.dp,
    )

    /** Which appearance the screens are drawn in: which of the two palettes is used. */
    public enum class Appearance {
        /** Whatever the device is set to. The default. */
        System,

        /** Always light, even on a device in dark mode. */
        Light,

        /** Always dark, even on a device in light mode. */
        Dark,
    }

    public companion object {
        /** The neutral palettes and the system font. */
        @JvmField
        public val Default: TalqynTheme = TalqynTheme()
    }
}

/** An icon of the theme: one of the SDK's vectors, or the app's own. */
@Immutable
public sealed interface TalqynIcon {
    /** A vector built in code, such as the SDK's own. */
    public data class Vector(val image: ImageVector) : TalqynIcon

    /** A drawable resource of the app — a vector drawable, as a rule. */
    public data class Resource(@param:DrawableRes val id: Int) : TalqynIcon
}

/** The palette the current appearance calls for. */
@Composable
@ReadOnlyComposable
internal fun TalqynTheme.resolvedColors(): TalqynTheme.Colors {
    val dark = when (appearance) {
        TalqynTheme.Appearance.System -> isSystemInDarkTheme()
        TalqynTheme.Appearance.Light -> false
        TalqynTheme.Appearance.Dark -> true
    }
    return if (dark) darkColors ?: colors else colors
}
