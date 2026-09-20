// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.MaterialSharedAxis
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.adapters.StatisticsAdapter
import dev.lemon.lemon_emu.databinding.FragmentStatisticsBinding
import dev.lemon.lemon_emu.model.GameStatEntry
import dev.lemon.lemon_emu.model.GamesViewModel
import dev.lemon.lemon_emu.model.HomeViewModel
import dev.lemon.lemon_emu.utils.PlayTimeUtils
import dev.lemon.lemon_emu.utils.ViewUtils.updateMargins

class StatisticsFragment : Fragment() {
    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val gamesViewModel: GamesViewModel by activityViewModels()

    private var entries: List<GameStatEntry> = emptyList()
    private var currentSortType: Int = R.id.sort_playtime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatisticsBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        homeViewModel.setStatusBarShadeVisibility(visible = false)

        binding.toolbarStatistics.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        entries = gamesViewModel.games.value.map { game ->
            GameStatEntry(
                game = game,
                playTimeSeconds = NativeLibrary.playTimeManagerGetPlayTime(game.programId),
                lastPlayedMillis = prefs.getLong(game.keyLastPlayedTime, 0L),
                sessionCount = prefs.getInt(game.keySessionCount, 0)
            )
        }

        binding.textSummaryGames.text =
            getString(R.string.statistics_summary_games, entries.size)
        val totalPlayTimeSeconds = entries.sumOf { it.playTimeSeconds }
        binding.textSummaryPlaytime.text = getString(
            R.string.statistics_summary_playtime,
            PlayTimeUtils.formatReadable(requireContext(), totalPlayTimeSeconds)
        )

        binding.listStatistics.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = StatisticsAdapter(sortedEntries())
        }

        binding.sortButton.setOnClickListener { showSortMenu(it) }

        setInsets()
    }

    private fun sortedEntries(): List<GameStatEntry> = when (currentSortType) {
        R.id.sort_last_played -> entries.sortedByDescending { it.lastPlayedMillis }
        R.id.sort_sessions -> entries.sortedByDescending { it.sessionCount }
        else -> entries.sortedByDescending { it.playTimeSeconds }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_statistics_sort, popup.menu)
        popup.menu.findItem(currentSortType)?.isChecked = true

        popup.setOnMenuItemClickListener { item ->
            currentSortType = item.itemId
            (binding.listStatistics.adapter as? StatisticsAdapter)?.replaceList(sortedEntries())
            true
        }

        popup.show()
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftInsets = barInsets.left + cutoutInsets.left
            val rightInsets = barInsets.right + cutoutInsets.right

            binding.appbarStatistics.updateMargins(left = leftInsets, right = rightInsets)
            binding.listStatistics.updateMargins(left = leftInsets, right = rightInsets)

            binding.listStatistics.updatePadding(bottom = barInsets.bottom)

            windowInsets
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
