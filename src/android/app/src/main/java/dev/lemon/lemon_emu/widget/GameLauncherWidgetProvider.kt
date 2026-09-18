// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import androidx.preference.PreferenceManager
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.activities.EmulationActivity
import dev.lemon.lemon_emu.ui.main.MainActivity
import dev.lemon.lemon_emu.utils.GameMetadata

// Shows the most recently launched game (set by GameAdapter.onClick alongside its
// keyLastPlayedTime bookkeeping) and launches straight into it on tap. Falls back to opening
// the app if nothing has been played yet.
class GameLauncherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // GameMetadata.getIcon() is a synchronous JNI call that reads/decrypts from the ROM -
        // everywhere else in the app it's dispatched off the main thread (GameIconFetcher runs
        // it via Coil's IO dispatcher), so do the same here rather than risk jank on this
        // system-triggered periodic update.
        val pendingResult = goAsync()
        Thread {
            try {
                for (widgetId in appWidgetIds) {
                    appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, widgetId))
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val PREF_LAST_GAME_PATH = "widget_last_game_path"
        private const val PREF_LAST_GAME_TITLE = "widget_last_game_title"

        // Callers are expected to already be off the main thread (GameAdapter.onClick already
        // does its shortcut bookkeeping inside Dispatchers.IO).
        fun setLastPlayedGame(context: Context, path: String, title: String) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_LAST_GAME_PATH, path)
                .putString(PREF_LAST_GAME_TITLE, title)
                .apply()
            updateAll(context)
        }

        private fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, GameLauncherWidgetProvider::class.java)
            )
            for (widgetId in ids) {
                manager.updateAppWidget(widgetId, buildRemoteViews(context, widgetId))
            }
        }

        private fun buildRemoteViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_game_launcher)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val path = prefs.getString(PREF_LAST_GAME_PATH, null)
            val title = prefs.getString(PREF_LAST_GAME_TITLE, null)

            val pendingIntent = if (path != null && title != null) {
                views.setTextViewText(R.id.widget_game_title, title)

                val icon = runCatching {
                    val data = GameMetadata.getIcon(path)
                    BitmapFactory.decodeByteArray(data, 0, data.size)
                }.getOrNull()
                if (icon != null) {
                    views.setImageViewBitmap(R.id.widget_game_icon, icon)
                } else {
                    views.setImageViewResource(R.id.widget_game_icon, R.drawable.ic_launcher_foreground)
                }

                val launchIntent = Intent(context, EmulationActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(path)
                }
                PendingIntent.getActivity(
                    context, widgetId, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                views.setTextViewText(R.id.widget_game_title, context.getString(R.string.widget_no_recent_game))
                views.setImageViewResource(R.id.widget_game_icon, R.drawable.ic_launcher_foreground)

                val openAppIntent = Intent(context, MainActivity::class.java)
                PendingIntent.getActivity(
                    context, widgetId, openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            return views
        }
    }
}
