package com.talqyn.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynUiStrings
import kotlin.coroutines.cancellation.CancellationException

/** What every composable of a screen reads: the theme with its palette resolved, the copy, and the helpers. */
@Immutable
internal class TalqynEnvironment(
    val theme: TalqynTheme,
    val colors: TalqynTheme.Colors,
    val strings: TalqynUiStrings,
    val price: TalqynPriceFormatter,
    val imageLoader: TalqynImageLoader,
) {
    val fonts: TalqynTheme.Fonts get() = theme.fonts
    val icons: TalqynTheme.Icons get() = theme.icons
    val metrics: TalqynTheme.Metrics get() = theme.metrics
}

internal val LocalTalqyn = staticCompositionLocalOf<TalqynEnvironment> {
    error("Talqyn composables are drawn inside a Talqyn screen")
}

/**
 * Resolves the theme for the current appearance and hands it down: the palette, a font scale
 * capped at the theme's maximum, and a Material theme built from the palette, so the sheet,
 * dialogs, and progress indicators the screens borrow wear the app's colors too.
 */
@Composable
internal fun ProvideTalqyn(
    theme: TalqynTheme,
    strings: TalqynUiStrings,
    price: TalqynPriceFormatter,
    imageLoader: TalqynImageLoader,
    content: @Composable () -> Unit,
) {
    val colors = theme.resolvedColors()
    val environment = remember(theme, colors, strings, price, imageLoader) {
        TalqynEnvironment(theme, colors, strings, price, imageLoader)
    }
    val isDark = colors.background.luminance() < 0.5f
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        onSurfaceVariant = colors.textSecondary,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.surfaceSecondary,
        outline = colors.border,
        outlineVariant = colors.border,
        error = colors.error,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalTalqyn provides environment,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.accent,
                backgroundColor = colors.accent.copy(alpha = 0.3f),
            ),
        ) {
            ProvideTalqynDensity(content)
        }
    }
}

/**
 * Caps the font scale at the theme's maximum for [content].
 *
 * [ProvideTalqyn] does it over a whole screen. A window the screen opens — a sheet, a dialog, a
 * menu — composes its content under the density of its own view again, so the content of such a
 * window calls this once more: otherwise a question in the clarify sheet would grow past the size
 * the same question is capped at in the transcript.
 */
@Composable
internal fun ProvideTalqynDensity(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val maximumScale = LocalTalqyn.current.fonts.maximumScale.coerceAtLeast(1f)
    val capped = remember(density, maximumScale) {
        if (density.fontScale <= maximumScale) density else Density(density.density, fontScale = maximumScale)
    }
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

// region Motion and haptics

/**
 * Whether the shopper asked the system for less movement — "Remove animations", which sets the
 * animator duration scale to zero. Animations then go without travel; the change still happens.
 */
@Composable
internal fun isReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** A light knock for taps that mean something — a chip picked, a question sent — unless the theme turned haptics off. */
@Composable
internal fun rememberTalqynHaptics(): () -> Unit {
    val view = LocalView.current
    val enabled = LocalTalqyn.current.theme.hapticsEnabled
    return remember(view, enabled) {
        { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    }
}

// endregion

// region Layers and the window

/**
 * Makes a layer drawn over the screen take the touches that land on it.
 *
 * Compose hands a touch to the topmost layer that handles pointer input and to nothing under it;
 * a layer that handles none — a background, a column of text — lets the touch through to
 * whatever it covers. Without this, a tap on a blank part of the comparison would open the
 * product card of the transcript beneath.
 */
internal fun Modifier.talqynBlocksTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/**
 * Sets the status and navigation bar icons to read against what the screen draws under them
 * while it is shown, and puts back what it found when it leaves.
 *
 * An app sets its bars for the device's appearance. A theme drawn light on a device in dark mode
 * — or dark in light mode — would otherwise keep white icons over a white header.
 *
 * @param top What is under the status bar, or `null` when the screen does not reach it: the app
 *   draws its own header there.
 * @param bottom What is under the navigation bar.
 */
@Composable
internal fun TalqynSystemBars(top: Color?, bottom: Color) {
    val view = LocalView.current
    val lightTop = top?.let { it.luminance() > 0.5f }
    val lightBottom = bottom.luminance() > 0.5f
    DisposableEffect(view, lightTop, lightBottom) {
        val window = if (view.isInEditMode) null else view.findWindow()
        if (window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val wasLightTop = controller.isAppearanceLightStatusBars
        val wasLightBottom = controller.isAppearanceLightNavigationBars
        // Before Android 10 an edge-to-edge app paints a scrim of its own under the navigation
        // buttons, and their icons belong to that scrim rather than to the composer behind it.
        // From 10 on, `enableEdgeToEdge` leaves the scrim to the system, which matches it to the icons.
        val setsBottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (lightTop != null) controller.isAppearanceLightStatusBars = lightTop
        if (setsBottom) controller.isAppearanceLightNavigationBars = lightBottom
        onDispose {
            if (lightTop != null) controller.isAppearanceLightStatusBars = wasLightTop
            if (setsBottom) controller.isAppearanceLightNavigationBars = wasLightBottom
        }
    }
}

private fun View.findWindow(): Window? = (parent as? DialogWindowProvider)?.window ?: context.findActivity()?.window

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// endregion

// region Building blocks

/** The painter of a theme icon. */
@Composable
internal fun TalqynIcon.painter(): Painter = when (this) {
    is TalqynIcon.Vector -> rememberVectorPainter(image)
    is TalqynIcon.Resource -> painterResource(id)
}

/** A theme icon, tinted; nothing at all when the role has no icon. */
@Composable
internal fun TalqynIconImage(
    icon: TalqynIcon?,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (icon == null) return
    Image(
        painter = icon.painter(),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/**
 * The palette's shadow under a shape, drawn rather than cast.
 *
 * An elevation shadow ignores its color before Android 9 and scales its alpha by the system's own
 * ambient and spot alphas, so the palette's `shadow` — its strength carried in the alpha — would
 * be neither the shadow the palette states nor the one the twin SDK draws. A transparent shadow
 * draws nothing at all.
 *
 * @param strength How much of the palette's shadow the shape takes.
 */
internal fun Modifier.talqynShadow(shape: Shape, color: Color, radius: Dp, offsetY: Dp, strength: Float = 1f): Modifier {
    val alpha = (color.alpha * strength).coerceIn(0f, 1f)
    if (alpha == 0f) return this
    return dropShadow(shape, Shadow(radius = radius, color = color.copy(alpha = alpha), offset = DpOffset(0.dp, offsetY)))
}

/**
 * Dims and shrinks while pressed, like the tappable cards and chips of the design, and takes
 * the tap. Compose extends the touch area of anything smaller than the minimum target, so a
 * control drawn at its design size still takes a finger.
 */
internal fun Modifier.talqynPressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = isReducedMotion()
    val alpha by animateFloatAsState(if (pressed) 0.6f else 1f, tween(if (pressed) 80 else 200), label = "pressAlpha")
    // The shrink is travel, which is what reduced motion asks about; the dim is feedback, and it stays.
    val scale by animateFloatAsState(if (pressed && !reduced) 0.98f else 1f, tween(if (pressed) 80 else 200), label = "pressScale")
    this
        .graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}

/** A hairline separator in the theme's border color. */
@Composable
internal fun TalqynDivider(modifier: Modifier = Modifier, thickness: Dp = 0.5.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .background(LocalTalqyn.current.colors.border),
    )
}

/** How a pill button is filled. */
internal enum class TalqynPillStyle { Accent, Outline }

/** A filled or outlined pill: "continue", "open results". */
@Composable
internal fun TalqynPillButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TalqynPillStyle = TalqynPillStyle.Accent,
    height: Dp = 40.dp,
    enabled: Boolean = true,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val shape = RoundedCornerShape(height / 2)
    val background = if (style == TalqynPillStyle.Accent) colors.accent else colors.surface
    val outline = if (style == TalqynPillStyle.Accent) colors.accent else colors.border
    val text = if (style == TalqynPillStyle.Accent) colors.onAccent else colors.textPrimary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = height)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .background(background, shape)
            .border(1.dp, outline, shape)
            .talqynPressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        BasicText(title, style = talqyn.fonts.label.copy(color = text, textAlign = TextAlign.Center))
    }
}

/**
 * An image that loads its content from the theme's loader. Until the image is there — and
 * when it never comes — a small placeholder stands in the middle, so a card reads as a card
 * with a missing picture rather than as a grey hole. A loaded image fades in; a cached one
 * does not.
 */
@Composable
internal fun TalqynRemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val talqyn = LocalTalqyn.current
    val loader = talqyn.imageLoader
    var image by remember(url, loader) { mutableStateOf(url?.let(loader::cached)) }
    val appearsAtOnce = remember(url, loader) { image != null }
    val reduced = isReducedMotion()
    LaunchedEffect(url, loader) {
        if (url == null || image != null) return@LaunchedEffect
        image = try {
            loader.load(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.background(talqyn.colors.surfaceSecondary)) {
        val loaded = image
        if (loaded == null) {
            TalqynIconImage(talqyn.icons.imagePlaceholder, tint = talqyn.colors.textTertiary, size = 20.dp)
        } else {
            val fade = remember(loaded) { Animatable(if (appearsAtOnce || reduced) 1f else 0f) }
            LaunchedEffect(loaded) { fade.animateTo(1f, tween(200)) }
            Image(
                bitmap = loaded,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fade.value },
            )
        }
    }
}

/** Gives a composable a spoken label without making it a separate focus stop. */
internal fun Modifier.talqynLabel(label: String): Modifier = semantics { contentDescription = label }

// endregion
