// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.ironlemon.ironlemon_emulator.features.settings.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation.NavOptions
import androidx.recyclerview.widget.RecyclerView
import dev.ironlemon.ironlemon_emulator.SettingsNavigationDirections
import dev.ironlemon.ironlemon_emulator.features.settings.SettingsCategories
import dev.ironlemon.ironlemon_emulator.features.settings.SettingsSearchIndex
import dev.ironlemon.ironlemon_emulator.features.settings.model.view.SubmenuSetting
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonPage
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernSettingsList
import dev.ironlemon.ironlemon_emulator.ui.modern.ModernSupportedTypes
import dev.ironlemon.ironlemon_emulator.ui.modern.SettingsActions
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.MaterialSharedAxis
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.databinding.FragmentSettingsBinding
import dev.ironlemon.ironlemon_emulator.features.input.NativeInput
import dev.ironlemon.ironlemon_emulator.features.settings.model.Settings
import dev.ironlemon.ironlemon_emulator.fragments.MessageDialogFragment
import dev.ironlemon.ironlemon_emulator.utils.ViewUtils.updateMargins
import dev.ironlemon.ironlemon_emulator.utils.collect

class SettingsFragment : Fragment() {
    private lateinit var presenter: SettingsFragmentPresenter
    private var settingsAdapter: SettingsAdapter? = null

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SettingsFragmentArgs>()

    private val settingsViewModel: SettingsViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)

        val playerIndex = getPlayerIndex()
        if (playerIndex != -1) {
            NativeInput.loadInputProfiles()
            NativeInput.reloadInputDevices()
        }
    }

    override fun onResume() {
        super.onResume()
        // The Nextendo sign-in finishes in the browser; show the new state on the way back.
        if (args.menuTag == Settings.MenuTag.SECTION_NEXTENDO) {
            presenter.loadSettingsList(true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(layoutInflater)
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsAdapter = SettingsAdapter(this, requireContext())
        presenter = SettingsFragmentPresenter(
            settingsViewModel,
            settingsAdapter!!,
            args.menuTag
        ).also { it.activity = requireActivity() }

        binding.toolbarSettingsLayout.title = if (args.menuTag == Settings.MenuTag.SECTION_ROOT &&
            args.game != null
        ) {
            args.game!!.title
        } else {
            when (args.menuTag) {
                Settings.MenuTag.SECTION_INPUT_PLAYER_ONE -> Settings.getPlayerString(1)
                Settings.MenuTag.SECTION_INPUT_PLAYER_TWO -> Settings.getPlayerString(2)
                Settings.MenuTag.SECTION_INPUT_PLAYER_THREE -> Settings.getPlayerString(3)
                Settings.MenuTag.SECTION_INPUT_PLAYER_FOUR -> Settings.getPlayerString(4)
                Settings.MenuTag.SECTION_INPUT_PLAYER_FIVE -> Settings.getPlayerString(5)
                Settings.MenuTag.SECTION_INPUT_PLAYER_SIX -> Settings.getPlayerString(6)
                Settings.MenuTag.SECTION_INPUT_PLAYER_SEVEN -> Settings.getPlayerString(7)
                Settings.MenuTag.SECTION_INPUT_PLAYER_EIGHT -> Settings.getPlayerString(8)
                else -> getString(args.menuTag.titleId)
            }
        }
        binding.listSettings.apply {
            adapter = settingsAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.toolbarSettings.setNavigationOnClickListener {
            settingsViewModel.setShouldNavigateBack(true)
        }

        settingsViewModel.shouldReloadSettingsList.collect(
            viewLifecycleOwner,
            resetState = { settingsViewModel.setShouldReloadSettingsList(false) }
        ) { if (it) presenter.loadSettingsList() }
        settingsViewModel.adapterItemChanged.collect(
            viewLifecycleOwner,
            resetState = { settingsViewModel.setAdapterItemChanged(-1) }
        ) { if (it != -1) settingsAdapter?.notifyItemChanged(it) }
        settingsViewModel.datasetChanged.collect(
            viewLifecycleOwner,
            resetState = { settingsViewModel.setDatasetChanged(false) }
        ) { if (it) settingsAdapter?.notifyDataSetChanged() }
        settingsViewModel.reloadListAndNotifyDataset.collect(
            viewLifecycleOwner,
            resetState = { settingsViewModel.setReloadListAndNotifyDataset(false) }
        ) { if (it) presenter.loadSettingsList(true) }
        settingsViewModel.shouldShowResetInputDialog.collect(
            viewLifecycleOwner,
            resetState = { settingsViewModel.setShouldShowResetInputDialog(false) }
        ) {
            if (it) {
                MessageDialogFragment.newInstance(
                    activity = requireActivity(),
                    titleId = R.string.reset_mapping,
                    descriptionId = R.string.reset_mapping_description,
                    positiveAction = {
                        NativeInput.resetControllerMappings(getPlayerIndex())
                        settingsViewModel.setReloadListAndNotifyDataset(true)
                    },
                    negativeAction = {}
                ).show(parentFragmentManager, MessageDialogFragment.TAG)
            }
        }

        if (args.menuTag == Settings.MenuTag.SECTION_ROOT) {
            binding.toolbarSettings.inflateMenu(R.menu.menu_settings)
            binding.toolbarSettings.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_search -> {
                        view.findNavController()
                            .navigate(R.id.action_settingsFragment_to_settingsSearchFragment)
                        true
                    }

                    else -> false
                }
            }
        }

        presenter.onViewCreated()

        setInsets()
        showModernList(view)
    }

    /**
     * The Iron Lemon list (Compose), drawn over the classic one. A section with a row type it
     * can't draw keeps the classic list. Every tap goes to the adapter's own handler.
     */
    private fun showModernList(root: View) {
        val adapter = settingsAdapter ?: return
        val tick = mutableIntStateOf(0)
        val highlight = mutableIntStateOf(-1)
        val title = binding.toolbarSettingsLayout.title?.toString().orEmpty()
        val rail = SettingsCategories.all.map {
            SubmenuSetting(
                titleId = it.titleId,
                descriptionId = it.descriptionId,
                iconId = it.iconId,
                menuKey = it.menuTag
            )
        }
        val actions = SettingsActions(
            onSwitch = adapter::onBooleanClick,
            onSingleChoice = adapter::onSingleChoiceClick,
            onStringSingleChoice = adapter::onStringSingleChoiceClick,
            onIntSingleChoice = adapter::onIntSingleChoiceClick,
            onSlider = adapter::onSliderClick,
            onDateTime = adapter::onDateTimeClick,
            onStringInput = adapter::onStringInputClick,
            onSubmenu = adapter::onSubmenuClick,
            onInput = adapter::onInputClick,
            onInputOptions = adapter::onInputOptionsClick,
            onInputProfile = adapter::onInputProfileClick,
            onClear = adapter::onClearClick,
            onLongClick = { item, position -> adapter.onLongClick(item, position) },
            onRailSection = { section ->
                if (section.menuKey != args.menuTag) {
                    val nav = root.findNavController()
                    val options = NavOptions.Builder()
                        .setPopUpTo(nav.currentDestination?.id ?: 0, true)
                        .build()
                    nav.navigate(
                        SettingsNavigationDirections.actionGlobalSettingsFragment(
                            section.menuKey,
                            args.game
                        ),
                        options
                    )
                }
            }
        )

        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                IronLemonPage(
                    title = title,
                    onBack = { settingsViewModel.setShouldNavigateBack(true) }
                ) {
                    ModernSettingsList(
                        items = adapter.currentList,
                        tick = tick.intValue,
                        isRoot = args.menuTag == Settings.MenuTag.SECTION_ROOT,
                        currentSection = args.menuTag.name,
                        railSections = rail,
                        actions = actions,
                        highlight = highlight.intValue,
                        onHighlightShown = { highlight.intValue = -1 }
                    )
                }
            }
        }
        (binding.root as ViewGroup).addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        fun refresh() {
            val modern = adapter.currentList.all { it.type in ModernSupportedTypes }
            composeView.visibility = if (modern) View.VISIBLE else View.GONE
            binding.appbarSettings.visibility = if (modern) View.GONE else View.VISIBLE
            binding.listSettings.visibility = if (modern) View.GONE else View.VISIBLE
            // A settings search result for this section: scroll to its row and flash it.
            SettingsSearchIndex.pendingTarget?.let { (tag, anchor) ->
                if (tag == args.menuTag) {
                    val index = adapter.currentList.indexOfFirst {
                        it.setting.key == anchor || it.title == anchor
                    }
                    if (index >= 0) {
                        highlight.intValue = index
                        SettingsSearchIndex.pendingTarget = null
                    }
                }
            }
            tick.intValue++
        }
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = refresh()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = refresh()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
                refresh()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refresh()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refresh()
        })
        refresh()
    }

    private fun getPlayerIndex(): Int =
        when (args.menuTag) {
            Settings.MenuTag.SECTION_INPUT_PLAYER_ONE -> 0
            Settings.MenuTag.SECTION_INPUT_PLAYER_TWO -> 1
            Settings.MenuTag.SECTION_INPUT_PLAYER_THREE -> 2
            Settings.MenuTag.SECTION_INPUT_PLAYER_FOUR -> 3
            Settings.MenuTag.SECTION_INPUT_PLAYER_FIVE -> 4
            Settings.MenuTag.SECTION_INPUT_PLAYER_SIX -> 5
            Settings.MenuTag.SECTION_INPUT_PLAYER_SEVEN -> 6
            Settings.MenuTag.SECTION_INPUT_PLAYER_EIGHT -> 7
            else -> -1
        }

    private fun setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftInsets = barInsets.left + cutoutInsets.left
            val rightInsets = barInsets.right + cutoutInsets.right

            binding.listSettings.updateMargins(left = leftInsets, right = rightInsets)
            binding.listSettings.updatePadding(bottom = barInsets.bottom)

            binding.appbarSettings.updateMargins(left = leftInsets, right = rightInsets)
            windowInsets
        }
    }
}
