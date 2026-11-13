// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class Installable(
    @StringRes val titleId: Int,
    @StringRes val descriptionId: Int,
    val install: (() -> Unit)? = null,
    val export: (() -> Unit)? = null,
    /** Shown instead of [descriptionId] when set (it depends on the device's state). */
    val descriptionText: String? = null,
    /** Label and icon of the [install] button, "Import" by default. */
    @StringRes val installLabelId: Int = 0,
    @DrawableRes val installIconRes: Int = 0
)
