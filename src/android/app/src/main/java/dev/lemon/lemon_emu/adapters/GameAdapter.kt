// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.adapters

import android.content.DialogInterface
import android.text.Html
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.lemon.lemon_emu.HomeNavigationDirections
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.LemonApplication
import dev.lemon.lemon_emu.databinding.CardGameListBinding
import dev.lemon.lemon_emu.databinding.CardGameGridBinding
import dev.lemon.lemon_emu.databinding.CardGameCarouselBinding
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.model.GamesViewModel
import dev.lemon.lemon_emu.utils.GameIconUtils
import dev.lemon.lemon_emu.utils.NativeConfig
import dev.lemon.lemon_emu.utils.PerformancePresets
import dev.lemon.lemon_emu.features.settings.utils.SettingsFile
import dev.lemon.lemon_emu.utils.ViewUtils.marquee
import dev.lemon.lemon_emu.viewholder.AbstractViewHolder
import androidx.core.net.toUri
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.databinding.CardGameGridCompactBinding
import dev.lemon.lemon_emu.features.settings.model.BooleanSetting
import dev.lemon.lemon_emu.features.settings.model.Settings
import dev.lemon.lemon_emu.features.settings.ui.SettingsSubscreen
import dev.lemon.lemon_emu.utils.GpuDriverHelper
import dev.lemon.lemon_emu.widget.GameLauncherWidgetProvider

class GameAdapter(private val activity: AppCompatActivity) :
    AbstractDiffAdapter<Game, GameAdapter.GameViewHolder>(exact = false) {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_GRID_COMPACT = 1
        const val VIEW_TYPE_LIST = 2
        const val VIEW_TYPE_CAROUSEL = 3
    }

    private var viewType = 0

    fun setViewType(type: Int) {
        viewType = type
        notifyDataSetChanged()
    }

    public var cardSize: Int = 0
        private set

    fun setCardSize(size: Int) {
        if (cardSize != size && size > 0) {
            cardSize = size
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int = viewType

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        when (getItemViewType(position)) {
            VIEW_TYPE_LIST -> {
                val listBinding = holder.binding as CardGameListBinding
                listBinding.cardGameList.scaleX = 1f
                listBinding.cardGameList.scaleY = 1f
                listBinding.cardGameList.alpha = 1f
                // Reset layout params to XML defaults
                listBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                listBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_GRID -> {
                val gridBinding = holder.binding as CardGameGridBinding
                gridBinding.cardGameGrid.scaleX = 1f
                gridBinding.cardGameGrid.scaleY = 1f
                gridBinding.cardGameGrid.alpha = 1f
                // Reset layout params to XML defaults
                gridBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                gridBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_GRID_COMPACT -> {
                val gridCompactBinding = holder.binding as CardGameGridCompactBinding
                gridCompactBinding.cardGameGridCompact.scaleX = 1f
                gridCompactBinding.cardGameGridCompact.scaleY = 1f
                gridCompactBinding.cardGameGridCompact.alpha = 1f
                // Reset layout params to XML defaults (same as normal grid)
                gridCompactBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                gridCompactBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_CAROUSEL -> {
                val carouselBinding = holder.binding as CardGameCarouselBinding
                // soothens transient flickering
                carouselBinding.cardGameCarousel.scaleY = 0f
                carouselBinding.cardGameCarousel.alpha = 0f
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = when (viewType) {
            VIEW_TYPE_LIST -> CardGameListBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_GRID -> CardGameGridBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_GRID_COMPACT -> CardGameGridCompactBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_CAROUSEL -> CardGameCarouselBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            else -> throw IllegalArgumentException("Invalid view type")
        }
        return GameViewHolder(binding, viewType)
    }

    inner class GameViewHolder(
        internal val binding: ViewBinding,
        private val viewType: Int
    ) : AbstractViewHolder<Game>(binding) {

        override fun bind(model: Game) {
            when (viewType) {
                VIEW_TYPE_LIST -> bindListView(model)
                VIEW_TYPE_GRID -> bindGridView(model)
                VIEW_TYPE_CAROUSEL -> bindCarouselView(model)
                VIEW_TYPE_GRID_COMPACT -> bindGridCompactView(model)
            }
        }

        private fun bindListView(model: Game) {
            val listBinding = binding as CardGameListBinding

            listBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, listBinding.imageGameScreen)

            listBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            listBinding.textGameDeveloper.text = model.developer

            listBinding.textGameTitle.marquee()
            listBinding.cardGameList.setOnClickListener { onClick(model) }
            listBinding.cardGameList.setOnLongClickListener { onLongClick(model) }

            // Reset layout params to XML defaults
            listBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            listBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindGridView(model: Game) {
            val gridBinding = binding as CardGameGridBinding

            gridBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, gridBinding.imageGameScreen)

            gridBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")

            gridBinding.textGameTitle.marquee()
            gridBinding.cardGameGrid.setOnClickListener { onClick(model) }
            gridBinding.cardGameGrid.setOnLongClickListener { onLongClick(model) }

            // Reset layout params to XML defaults
            gridBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            gridBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindGridCompactView(model: Game) {
            val gridCompactBinding = binding as CardGameGridCompactBinding

            gridCompactBinding.imageGameScreenCompact.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, gridCompactBinding.imageGameScreenCompact)

            gridCompactBinding.textGameTitleCompact.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")

            gridCompactBinding.textGameTitleCompact.marquee()
            gridCompactBinding.cardGameGridCompact.setOnClickListener { onClick(model) }
            gridCompactBinding.cardGameGridCompact.setOnLongClickListener { onLongClick(model) }

            // Reset layout params to XML defaults (same as normal grid)
            gridCompactBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            gridCompactBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindCarouselView(model: Game) {
            val carouselBinding = binding as CardGameCarouselBinding

            carouselBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, carouselBinding.imageGameScreen)

            carouselBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            carouselBinding.textGameTitle.marquee()
            carouselBinding.cardGameCarousel.setOnClickListener { onClick(model) }
            carouselBinding.cardGameCarousel.setOnLongClickListener { onLongClick(model) }

            carouselBinding.imageGameScreen.contentDescription =
                binding.root.context.getString(R.string.game_image_desc, model.title)

            // Ensure zero-heighted-full-width cards for carousel
            carouselBinding.root.layoutParams.width = cardSize
        }

        fun onClick(game: Game) {
            val gameExists = DocumentFile.fromSingleUri(
                LemonApplication.appContext,
                game.path.toUri()
            )?.exists() == true

            if (!gameExists) {
                Toast.makeText(
                    LemonApplication.appContext,
                    R.string.loader_error_file_not_found,
                    Toast.LENGTH_LONG
                ).show()

                ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(true)
                return
            }

            val launch: () -> Unit = {
                val preferences =
                    PreferenceManager.getDefaultSharedPreferences(LemonApplication.appContext)
                preferences.edit {
                    putLong(
                        game.keyLastPlayedTime,
                        System.currentTimeMillis()
                    )
                }

                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val shortcut =
                            ShortcutInfoCompat.Builder(LemonApplication.appContext, game.path)
                                .setShortLabel(game.title)
                                .setIcon(GameIconUtils.getShortcutIcon(activity, game))
                                .setIntent(game.launchIntent)
                                .build()
                        ShortcutManagerCompat.pushDynamicShortcut(
                            LemonApplication.appContext,
                            shortcut
                        )
                        GameLauncherWidgetProvider.setLastPlayedGame(
                            LemonApplication.appContext,
                            game.path,
                            game.title
                        )
                    }
                }

                val action = HomeNavigationDirections.actionGlobalEmulationActivity(game, true)
                binding.root.findNavController().navigate(action)
            }

            if (NativeLibrary.gameRequiresFirmware(game.programId) && !NativeLibrary.isFirmwareAvailable()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.loader_requires_firmware)
                    .setMessage(
                        Html.fromHtml(
                            activity.getString(R.string.loader_requires_firmware_description),
                            Html.FROM_HTML_MODE_LEGACY
                        )
                    )
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                        launch()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
            } else {
                launch()
            }
        }

        fun onLongClick(game: Game): Boolean {
            val popup = PopupMenu(activity, binding.root)

            val playId = 0
            val driverId = 1
            val propertiesId = 2
            val performanceId = 3
            val favoriteId = 4
            val shortcutId = 5

            val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
            val isFavorite = preferences.getBoolean(game.keyIsFavorite, false)

            popup.menu.add(0, playId, 0, R.string.play)
            if (GpuDriverHelper.isAdrenoGpu()) {
                popup.menu.add(0, driverId, 1, R.string.freedreno_per_game_title)
            }
            popup.menu.add(0, propertiesId, 2, R.string.per_game_settings)
            popup.menu.add(0, performanceId, 3, R.string.performance_preset)
            popup.menu.add(
                0, favoriteId, 4,
                if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
            )
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
                popup.menu.add(0, shortcutId, 5, R.string.add_to_home_screen)
            }

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    playId -> onClick(game)
                    driverId -> {
                        val action = HomeNavigationDirections
                            .actionGlobalSettingsSubscreenActivity(
                                SettingsSubscreen.FREEDRENO_SETTINGS,
                                game
                            )
                        binding.root.findNavController().navigate(action)
                    }

                    propertiesId -> {
                        val action =
                            HomeNavigationDirections.actionGlobalPerGamePropertiesFragment(game)
                        binding.root.findNavController().navigate(action)
                    }

                    performanceId -> showPerformancePresetDialog(game)
                    favoriteId -> {
                        preferences.edit { putBoolean(game.keyIsFavorite, !isFavorite) }
                    }
                    shortcutId -> requestPinShortcut(game)
                }
                true
            }

            popup.show()
            return true
        }

        private fun requestPinShortcut(game: Game) {
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val shortcut = ShortcutInfoCompat.Builder(activity, "pin_${game.path}")
                        .setShortLabel(game.title)
                        .setIcon(GameIconUtils.getShortcutIcon(activity, game))
                        .setIntent(game.launchIntent)
                        .build()
                    ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
                }
            }
        }

        private fun showPerformancePresetDialog(game: Game) {
            val presets = PerformancePresets.Preset.entries.toTypedArray()
            val labels = presets.map { activity.getString(it.titleRes) }.toTypedArray()

            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.performance_preset)
                .setItems(labels) { dialog, which ->
                    val preset = presets[which]
                    SettingsFile.loadCustomConfig(game)
                    PerformancePresets.apply(preset)
                    NativeConfig.savePerGameConfig()
                    NativeConfig.unloadPerGameConfig()
                    dialog.dismiss()
                    Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.preset_applied_per_game,
                            activity.getString(preset.titleRes)
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
                .show()
        }
    }
}
