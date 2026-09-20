// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.adapters

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.lemon.lemon_emu.HomeNavigationDirections
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.CardGameListBinding
import dev.lemon.lemon_emu.databinding.CardGameGridBinding
import dev.lemon.lemon_emu.databinding.CardGameCarouselBinding
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.utils.GameIconUtils
import dev.lemon.lemon_emu.utils.GameLaunchUtils
import dev.lemon.lemon_emu.utils.GameStatsUtils
import dev.lemon.lemon_emu.utils.NativeConfig
import dev.lemon.lemon_emu.utils.PerformancePresets
import dev.lemon.lemon_emu.features.settings.utils.SettingsFile
import dev.lemon.lemon_emu.utils.ViewUtils.marquee
import dev.lemon.lemon_emu.viewholder.AbstractViewHolder
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.databinding.CardGameGridCompactBinding
import dev.lemon.lemon_emu.features.settings.model.BooleanSetting
import dev.lemon.lemon_emu.features.settings.model.Settings
import dev.lemon.lemon_emu.features.settings.ui.SettingsSubscreen
import dev.lemon.lemon_emu.utils.GpuDriverHelper

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

        // Raw screen touch point, tracked only for carousel cards - see onLongClick.
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        // Currently bound game, used by setCarouselCenterState (called from
        // CarouselRecyclerView, outside the normal bind() flow) to know what to show.
        private var boundGame: Game? = null

        override fun bind(model: Game) {
            boundGame = model
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
            val abbreviatedStats = GameStatsUtils.buildAbbreviated(activity, model)
            listBinding.textGameDeveloper.text = if (abbreviatedStats != null) {
                "${model.developer} · $abbreviatedStats"
            } else {
                model.developer
            }

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
            val abbreviatedStats = GameStatsUtils.buildAbbreviated(activity, model)
            gridBinding.textGameStats.text = abbreviatedStats
            gridBinding.textGameStats.visibility = if (abbreviatedStats != null) View.VISIBLE else View.GONE

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
            val abbreviatedStats = GameStatsUtils.buildAbbreviated(activity, model)
            gridCompactBinding.textGameStatsCompact.text = abbreviatedStats
            gridCompactBinding.textGameStatsCompact.visibility =
                if (abbreviatedStats != null) View.VISIBLE else View.GONE

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
            carouselBinding.cardGameCarousel.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                false
            }

            carouselBinding.imageGameScreen.contentDescription =
                binding.root.context.getString(R.string.game_image_desc, model.title)

            // Ensure zero-heighted-full-width cards for carousel
            carouselBinding.root.layoutParams.width = cardSize

            // Reset to hidden on (re)bind - a recycled holder may have been left showing
            // stats for a previous game as it scrolled out of the center position.
            carouselBinding.textGameStats.visibility = View.GONE
            carouselBinding.overlayGameStatsBackground.visibility = View.GONE
            carouselBinding.textGameStats.isSelected = false
        }

        /**
         * Shows or hides the carousel card's stats overlay + marquee. Only meant to be called
         * for the single centered card at a time (see CarouselRecyclerView), so at most one
         * marquee animates on screen. Cheap to call every scroll frame - it no-ops unless the
         * center state actually changed.
         */
        fun setCarouselCenterState(isCenter: Boolean) {
            if (viewType != VIEW_TYPE_CAROUSEL) return
            val carouselBinding = binding as CardGameCarouselBinding
            val game = boundGame ?: return

            if (isCenter) {
                if (carouselBinding.textGameStats.visibility == View.VISIBLE) return
                val summary = GameStatsUtils.buildFullSummary(activity, game) ?: return
                carouselBinding.textGameStats.text = summary
                carouselBinding.textGameStats.visibility = View.VISIBLE
                carouselBinding.overlayGameStatsBackground.visibility = View.VISIBLE
                carouselBinding.textGameStats.marquee()
            } else {
                if (carouselBinding.textGameStats.visibility != View.VISIBLE) return
                carouselBinding.textGameStats.visibility = View.GONE
                carouselBinding.overlayGameStatsBackground.visibility = View.GONE
                carouselBinding.textGameStats.isSelected = false
            }
        }

        fun onClick(game: Game) {
            GameLaunchUtils.launchGame(activity, game, binding.root.findNavController())
        }

        private fun buildAndShowPopup(game: Game, anchor: View, onDismiss: (() -> Unit)? = null) {
            val popup = PopupMenu(activity, anchor)
            if (onDismiss != null) {
                popup.setOnDismissListener { onDismiss() }
            }

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
        }

        fun onLongClick(game: Game): Boolean {
            // In carousel mode, cards are positioned via translationX/scaleX/scaleY
            // (see CarouselRecyclerView) rather than real layout bounds, so neither the
            // card's own bounds nor its parent's bounds (both used by PopupMenu's
            // automatic anchoring via View#getLocationOnScreen, which ignores render
            // transforms) reflect where the card actually appears on screen. Anchor to
            // a 1x1 proxy view positioned via real layout params (margins) at the touch
            // point instead, and wait for a genuine layout pass (.post) before showing -
            // a manually-forced layout() has the coordinates right but PopupMenu's
            // internal positioning still misbehaves without a real traversal.
            val decorView = activity.window.decorView as? ViewGroup
            if (viewType == VIEW_TYPE_CAROUSEL && decorView != null) {
                val decorLocation = IntArray(2)
                decorView.getLocationOnScreen(decorLocation)
                val localX = (lastTouchX - decorLocation[0]).toInt().coerceAtLeast(0)
                val localY = (lastTouchY - decorLocation[1]).toInt().coerceAtLeast(0)

                val proxy = View(activity)
                val lp = FrameLayout.LayoutParams(1, 1)
                lp.leftMargin = localX
                lp.topMargin = localY
                decorView.addView(proxy, lp)

                proxy.post {
                    buildAndShowPopup(game, proxy) { decorView.removeView(proxy) }
                }
            } else {
                buildAndShowPopup(game, binding.root)
            }
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
