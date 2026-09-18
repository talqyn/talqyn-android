package com.talqyn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The SDK's own icons, drawn on a 24-unit grid in the Material style, so the screens need no
 * icon library of anybody's. Each is a mask: the theme's tint colors it.
 */
public object TalqynVectors {
    private const val CIRCLE = "M12,2A10,10 0 1,1 12,22A10,10 0 1,1 12,2Z"

    public val Sparkles: ImageVector by lazy {
        icon(
            "sparkles",
            "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9z" +
                "M11.5,9.5L9,4 6.5,9.5 1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5z" +
                "M19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z",
        )
    }

    public val History: ImageVector by lazy {
        icon(
            "history",
            "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 " +
                "-3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 " +
                "9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
        )
    }

    public val NewChat: ImageVector by lazy {
        icon(
            "new_chat",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM5.92,19H5v-0.92l9.06,-9.06 0.92,0.92L5.92,19z" +
                "M20.71,5.63l-2.34,-2.34c-0.2,-0.2 -0.45,-0.29 -0.71,-0.29s-0.51,0.1 -0.7,0.29l-1.83,1.83 " +
                "3.75,3.75 1.83,-1.83c0.39,-0.39 0.39,-1.02 0,-1.41z",
        )
    }

    /** A filled circle with the arrow cut out of it. */
    public val Send: ImageVector by lazy { icon("send", CIRCLE + "M12,6.5l-5,5h3.5v6h3v-6H17z", evenOdd = true) }

    /** A filled circle with the square cut out of it. */
    public val Stop: ImageVector by lazy { icon("stop", CIRCLE + "M8.5,8.5h7v7h-7z", evenOdd = true) }

    public val ArrowDown: ImageVector by lazy {
        icon("arrow_down", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
    }

    public val Close: ImageVector by lazy {
        icon(
            "close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        )
    }

    public val Back: ImageVector by lazy {
        icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", autoMirror = true)
    }

    public val Check: ImageVector by lazy { icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }

    public val ThumbUp: ImageVector by lazy {
        icon(
            "thumb_up",
            "M9,21h9c0.83,0 1.54,-0.5 1.84,-1.22l3.02,-7.05c0.09,-0.23 0.14,-0.47 0.14,-0.73v-2c0,-1.1 -0.9,-2 " +
                "-2,-2h-6.31l0.95,-4.57 0.03,-0.32c0,-0.41 -0.17,-0.79 -0.44,-1.06L14.17,1 7.58,7.59C7.22,7.95 7,8.45 " +
                "7,9v10c0,1.1 0.9,2 2,2zM9,9l4.34,-4.34L12,10h9v2l-3,7H9V9zM1,9h4v12H1z",
        )
    }

    public val ThumbUpFilled: ImageVector by lazy {
        icon(
            "thumb_up_filled",
            "M1,21h4V9H1v12zM23,10c0,-1.1 -0.9,-2 -2,-2h-6.31l0.95,-4.57 0.03,-0.32c0,-0.41 -0.17,-0.79 " +
                "-0.44,-1.06L14.17,1 7.59,7.59C7.22,7.95 7,8.45 7,9v10c0,1.1 0.9,2 2,2h9c0.83,0 1.54,-0.5 " +
                "1.84,-1.22l3.02,-7.05c0.09,-0.23 0.14,-0.47 0.14,-0.73v-2z",
        )
    }

    public val ThumbDown: ImageVector by lazy {
        icon(
            "thumb_down",
            "M15,3H6c-0.83,0 -1.54,0.5 -1.84,1.22l-3.02,7.05c-0.09,0.23 -0.14,0.47 -0.14,0.73v2c0,1.1 0.9,2 " +
                "2,2h6.31l-0.95,4.57 -0.03,0.32c0,0.41 0.17,0.79 0.44,1.06L9.83,23l6.59,-6.59c0.36,-0.36 0.58,-0.86 " +
                "0.58,-1.41V5c0,-1.1 -0.9,-2 -2,-2zM15,15l-4.34,4.34L12,14H3v-2l3,-7h9v10zM19,3h4v12h-4z",
        )
    }

    public val ThumbDownFilled: ImageVector by lazy {
        icon(
            "thumb_down_filled",
            "M15,3H6c-0.83,0 -1.54,0.5 -1.84,1.22l-3.02,7.05c-0.09,0.23 -0.14,0.47 -0.14,0.73v2c0,1.1 0.9,2 " +
                "2,2h6.31l-0.95,4.57 -0.03,0.32c0,0.41 0.17,0.79 0.44,1.06L9.83,23l6.59,-6.59c0.36,-0.36 0.58,-0.86 " +
                "0.58,-1.41V5c0,-1.1 -0.9,-2 -2,-2zM19,3v12h4V3h-4z",
        )
    }

    public val Copy: ImageVector by lazy {
        icon(
            "copy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 " +
                "2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
        )
    }

    public val Delete: ImageVector by lazy {
        icon(
            "delete",
            "M16,9v10H8V9h8m-1.5,-6h-5l-1,1H5v2h14V4h-3.5l-1,-1zM18,7H6v12c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7z",
        )
    }

    public val Search: ImageVector by lazy {
        icon(
            "search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 " +
                "9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 " +
                "5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        )
    }

    public val Compare: ImageVector by lazy {
        icon("compare", "M9.01,14H2v2h7.01v3L13,15l-3.99,-4v3zM14.99,13v-3H22V8h-7.01V5L11,9l3.99,4z")
    }

    public val Edit: ImageVector by lazy {
        icon(
            "edit",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34" +
                "c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        )
    }

    public val Image: ImageVector by lazy {
        icon(
            "image",
            "M19,5v14H5V5h14m0,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 " +
                "-2,-2zM14.14,11.86l-3,3.87L9,13.14 6,17h12l-3.86,-5.14z",
        )
    }

    public val Star: ImageVector by lazy {
        icon("star", "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z")
    }

    public val StarOutline: ImageVector by lazy {
        icon(
            "star_outline",
            "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24z" +
                "M12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
        )
    }

    private fun icon(name: String, pathData: String, evenOdd: Boolean = false, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = "Talqyn.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).addPath(
            pathData = addPathNodes(pathData),
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            fill = SolidColor(Color.Black),
        ).build()
}
