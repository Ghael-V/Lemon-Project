// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.ui.modern.UiMode

/**
 * The library's place in the navigation graph. It shows the new library or the classic one
 * ([GamesFragment]) according to the "New interface" setting, and swaps them when that changes.
 */
class LibraryHostFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentContainerView(requireContext()).apply { id = R.id.library_container }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showLibrary()
    }

    override fun onResume() {
        super.onResume()
        showLibrary()
    }

    private fun showLibrary() {
        val modern = UiMode.isModern(requireContext())
        val current = childFragmentManager.findFragmentById(R.id.library_container)
        val correct = if (modern) current is ModernGamesFragment else current is GamesFragment
        if (correct || childFragmentManager.isStateSaved) {
            return
        }
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.library_container, if (modern) ModernGamesFragment() else GamesFragment())
            .commit()
    }
}
