// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.fragments

import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.model.License
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonActionCard
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonCardGrid
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonComposeFragment
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage

/** The licenses of the projects the app is built with; a tap shows the full text. */
class LicensesFragment : IronLemonComposeFragment() {
    private val licenses = listOf(
        License(
            R.string.license_fidelityfx_fsr,
            R.string.license_fidelityfx_fsr_description,
            R.string.license_fidelityfx_fsr_link,
            R.string.license_fidelityfx_fsr_copyright,
            R.string.license_fidelityfx_fsr_text
        ),
        License(
            R.string.license_cubeb,
            R.string.license_cubeb_description,
            R.string.license_cubeb_link,
            R.string.license_cubeb_copyright,
            R.string.license_cubeb_text
        ),
        License(
            R.string.license_dynarmic,
            R.string.license_dynarmic_description,
            R.string.license_dynarmic_link,
            R.string.license_dynarmic_copyright,
            R.string.license_dynarmic_text
        ),
        License(
            R.string.license_ffmpeg,
            R.string.license_ffmpeg_description,
            R.string.license_ffmpeg_link,
            R.string.license_ffmpeg_copyright,
            R.string.license_ffmpeg_text
        ),
        License(
            R.string.license_opus,
            R.string.license_opus_description,
            R.string.license_opus_link,
            R.string.license_opus_copyright,
            R.string.license_opus_text
        ),
        License(
            R.string.license_sirit,
            R.string.license_sirit_description,
            R.string.license_sirit_link,
            R.string.license_sirit_copyright,
            R.string.license_sirit_text
        ),
        License(
            R.string.license_adreno_tools,
            R.string.license_adreno_tools_description,
            R.string.license_adreno_tools_link,
            R.string.license_adreno_tools_copyright,
            R.string.license_adreno_tools_text
        )
    )

    @Composable
    override fun ScreenContent() {
        IronLemonPage(title = stringResource(R.string.licenses), onBack = ::goBack) {
            IronLemonCardGrid {
                items(licenses) { license ->
                    IronLemonActionCard(
                        title = stringResource(license.titleId),
                        description = stringResource(license.descriptionId),
                        onClick = {
                            LicenseBottomSheetDialogFragment.newInstance(license)
                                .show(parentFragmentManager, LicenseBottomSheetDialogFragment.TAG)
                        }
                    )
                }
            }
        }
    }
}
