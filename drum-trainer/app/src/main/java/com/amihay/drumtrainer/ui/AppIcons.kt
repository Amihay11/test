package com.amihay.drumtrainer.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector

/** Icons the core Material set does not have, drawn on its 24 × 24 grid. */
object AppIcons {
    val Drum: ImageVector by lazy {
        materialIcon("Drum") {
            // Shell: from the front edge of the head down to the bottom rim.
            materialPath {
                moveTo(4f, 9f)
                lineTo(4f, 16f)
                arcTo(8f, 3f, 0f, false, false, 20f, 16f)
                lineTo(20f, 9f)
                arcTo(8f, 3f, 0f, false, true, 4f, 9f)
                close()
            }
            // Head: a ring.
            materialPath(pathFillType = PathFillType.EvenOdd) {
                moveTo(4f, 9f)
                arcTo(8f, 3f, 0f, true, false, 20f, 9f)
                arcTo(8f, 3f, 0f, true, false, 4f, 9f)
                close()
                moveTo(6f, 9f)
                arcTo(6f, 1.8f, 0f, true, true, 18f, 9f)
                arcTo(6f, 1.8f, 0f, true, true, 6f, 9f)
                close()
            }
            // Sticks.
            materialPath {
                moveTo(15.2f, 1.2f); lineTo(19.6f, 5.4f); lineTo(18.8f, 6.2f); lineTo(14.4f, 2f); close()
                moveTo(8.8f, 1.2f); lineTo(4.4f, 5.4f); lineTo(5.2f, 6.2f); lineTo(9.6f, 2f); close()
            }
        }
    }

    val Chart: ImageVector by lazy {
        materialIcon("Chart") {
            materialPath {
                moveTo(4f, 13f); horizontalLineTo(7.5f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(10.25f, 8f); horizontalLineTo(13.75f); verticalLineTo(20f); horizontalLineTo(10.25f); close()
                moveTo(16.5f, 3f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(16.5f); close()
            }
        }
    }

    val Metronome: ImageVector by lazy {
        materialIcon("Metronome") {
            materialPath(pathFillType = PathFillType.EvenOdd) {
                moveTo(9f, 2f); horizontalLineTo(15f); lineTo(20f, 22f); horizontalLineTo(4f); close()
                moveTo(10.4f, 4f); horizontalLineTo(13.6f); lineTo(17.5f, 20f); horizontalLineTo(6.5f); close()
            }
            materialPath {
                moveTo(11.2f, 15.6f); lineTo(16.6f, 5.2f); lineTo(17.6f, 5.7f); lineTo(12.2f, 16.1f); close()
                moveTo(6.5f, 16.5f); horizontalLineTo(17.5f); verticalLineTo(18f); horizontalLineTo(6.5f); close()
            }
        }
    }

    /** Two crossed sticks: hand technique (rudiments). */
    val Sticks: ImageVector by lazy {
        materialIcon("Sticks") {
            materialPath {
                moveTo(4.2f, 2.8f); lineTo(21.2f, 19.8f); lineTo(19.8f, 21.2f); lineTo(2.8f, 4.2f); close()
                moveTo(19.8f, 2.8f); lineTo(21.2f, 4.2f); lineTo(4.2f, 21.2f); lineTo(2.8f, 19.8f); close()
            }
            materialPath {
                moveTo(3.5f, 1.5f); arcTo(2f, 2f, 0f, true, true, 3.49f, 1.5f); close()
                moveTo(20.5f, 1.5f); arcTo(2f, 2f, 0f, true, true, 20.49f, 1.5f); close()
            }
        }
    }

    /** Two hands' worth of taps: a finger on a pad. */
    val Tap: ImageVector by lazy {
        materialIcon("Tap") {
            materialPath(pathFillType = PathFillType.EvenOdd) {
                moveTo(5f, 3f); horizontalLineTo(19f); arcTo(2f, 2f, 0f, false, true, 21f, 5f)
                verticalLineTo(19f); arcTo(2f, 2f, 0f, false, true, 19f, 21f)
                horizontalLineTo(5f); arcTo(2f, 2f, 0f, false, true, 3f, 19f)
                verticalLineTo(5f); arcTo(2f, 2f, 0f, false, true, 5f, 3f); close()
                moveTo(5f, 5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
            }
            materialPath {
                moveTo(12f, 8f)
                arcTo(4f, 4f, 0f, true, true, 11.99f, 8f)
                close()
            }
        }
    }
}
