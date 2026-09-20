// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.databinding.OverlayCheatPanelBinding

/**
 * "Lemon Cheater": a live memory search/edit panel that floats above the emulation surface.
 *
 * Unlike [InputOverlay], this doesn't cover the whole screen with a single hit-tested View -
 * it's a plain FrameLayout holding one small, draggable card. Touches outside the card's
 * bounds are never claimed here, so they fall through to whatever's beneath (the game/input
 * overlay) via ordinary Android view dispatch - no custom routing needed for that part.
 *
 * Scope (v1): values are treated as 4-byte signed integers.
 */
class CheatOverlay(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private data class Match(val address: Long, val value: Int)

    private val binding: OverlayCheatPanelBinding
    private val adapter = CheatResultAdapter { address, value -> showWriteDialog(address, value) }

    private var lastMatches: List<Match> = emptyList()

    // Set by the blind-search button, cleared by the first Increased/Decreased tap after it.
    // While true, Increased/Decreased compares live memory against the native-side snapshot
    // (cheatCompareSnapshot); once cleared, they refine the existing candidate list instead
    // (cheatRefine), which is what lets increased/decreased chain across multiple passes.
    private var awaitingBlindCompare = false

    // Search/Refine can take real time on a big game's memory (hundreds of MB) - running that
    // on the UI thread froze the whole app. Everything native-side happens on
    // Dispatchers.Default here; only the resulting UI updates hop back to Main.
    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private var isBusy = false

    init {
        binding = OverlayCheatPanelBinding.inflate(LayoutInflater.from(context), this, false)
        addView(
            binding.root,
            // Centered: this used to open pinned to the top-left, which put it right behind
            // the in-game drawer menu that opens from that same side - it looked like the
            // panel never opened at all. It's still fully draggable afterward.
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        binding.cheatResultsList.layoutManager = LinearLayoutManager(context)
        binding.cheatResultsList.adapter = adapter

        binding.cheatPanelClose.setOnClickListener { hide() }
        binding.cheatSearchButton.setOnClickListener { onSearchClicked() }
        binding.cheatRefineButton.setOnClickListener { onRefineClicked() }
        binding.cheatBlindSearchButton.setOnClickListener { onBlindSearchClicked() }
        binding.cheatIncreasedButton.setOnClickListener { onComparisonClicked(Comparison.INCREASED) }
        binding.cheatDecreasedButton.setOnClickListener { onComparisonClicked(Comparison.DECREASED) }
        binding.cheatUnchangedButton.setOnClickListener { onComparisonClicked(Comparison.UNCHANGED) }
        binding.cheatValueInput.doOnTextChanged { _, _, _, _ ->
            binding.cheatValueInputLayout.error = null
        }

        setupDragHandle()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + Job())
        }
    }

    /**
     * Just hides the panel - the candidate list, displayed rows and any in-flight
     * search/refine are left alone, so reopening (from the in-game menu) picks up exactly
     * where it was. Search/refine passes are quick now (see memory_search.h), so letting one
     * finish unseen in the background while hidden is harmless.
     */
    private fun hide() {
        visibility = View.GONE
    }

    private fun setupDragHandle() {
        var startRawX = 0f
        var startRawY = 0f
        var startTranslationX = 0f
        var startTranslationY = 0f

        binding.cheatPanelTitleBar.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startTranslationX = binding.root.translationX
                    startTranslationY = binding.root.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    binding.root.translationX = startTranslationX + (event.rawX - startRawX)
                    binding.root.translationY = startTranslationY + (event.rawY - startRawY)
                    true
                }
                else -> false
            }
        }
    }

    private fun parseValueOrShowError(): Int? {
        val text = binding.cheatValueInput.text?.toString().orEmpty()
        val value = text.toIntOrNull()
        if (value == null) {
            binding.cheatValueInputLayout.error = context.getString(R.string.lemon_cheater_invalid_value)
        }
        return value
    }

    private fun onSearchClicked() {
        if (isBusy) return
        val value = parseValueOrShowError() ?: return
        runBusy {
            val results = withContext(Dispatchers.Default) { NativeLibrary.cheatSearch(value) }
            awaitingBlindCompare = false
            lastMatches = decodeMatches(results)
            refreshResults()
        }
    }

    private fun onRefineClicked() {
        if (isBusy) return
        val value = parseValueOrShowError() ?: return
        runBusy {
            val candidates = encodeMatches(lastMatches)
            val results = withContext(Dispatchers.Default) {
                NativeLibrary.cheatRefine(candidates, EXACT_COMPARISON, value)
            }
            lastMatches = decodeMatches(results)
            refreshResults()
        }
    }

    private fun onBlindSearchClicked() {
        if (isBusy) return
        runBusy {
            withContext(Dispatchers.Default) { NativeLibrary.cheatTakeSnapshot() }
            awaitingBlindCompare = true
            lastMatches = emptyList()
            adapter.submitResults(emptyList())
            binding.cheatStatusText.text = context.getString(R.string.lemon_cheater_snapshot_taken)
        }
    }

    private fun onComparisonClicked(comparison: Comparison) {
        if (isBusy) return
        runBusy {
            val results = withContext(Dispatchers.Default) {
                if (awaitingBlindCompare) {
                    NativeLibrary.cheatCompareSnapshot(comparison.nativeValue)
                } else {
                    NativeLibrary.cheatRefine(encodeMatches(lastMatches), comparison.nativeValue, 0)
                }
            }
            awaitingBlindCompare = false
            lastMatches = decodeMatches(results)
            refreshResults()
        }
    }

    /** Runs [block] with every action button disabled and a "working..." status. */
    private fun runBusy(block: suspend () -> Unit) {
        isBusy = true
        setButtonsEnabled(enabled = false)
        binding.cheatStatusText.text = context.getString(R.string.lemon_cheater_searching)

        scope.launch {
            try {
                block()
            } finally {
                isBusy = false
                setButtonsEnabled(enabled = true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.cheatSearchButton.isEnabled = enabled
        binding.cheatBlindSearchButton.isEnabled = enabled
        val hasCandidates = enabled && (awaitingBlindCompare || lastMatches.isNotEmpty())
        binding.cheatRefineButton.isEnabled = enabled && lastMatches.isNotEmpty()
        binding.cheatIncreasedButton.isEnabled = hasCandidates
        binding.cheatDecreasedButton.isEnabled = hasCandidates
        binding.cheatUnchangedButton.isEnabled = hasCandidates
    }

    /** Updates the results list and status text from [lastMatches] - no native calls needed,
     *  every path that sets [lastMatches] already returns each match's current value. */
    private fun refreshResults() {
        val total = lastMatches.size
        val rows = lastMatches.take(DISPLAY_LIMIT).map { it.address to it.value }
        adapter.submitResults(rows)

        binding.cheatStatusText.text = when {
            total == 0 -> context.getString(R.string.lemon_cheater_no_results)
            total > DISPLAY_LIMIT -> context.getString(
                R.string.lemon_cheater_showing_subset,
                rows.size,
                total
            )
            else -> context.getString(R.string.lemon_cheater_results_count, total)
        }
    }

    private fun showWriteDialog(address: Long, currentValue: Int) {
        val input = TextInputEditText(context).apply {
            setText(currentValue.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(String.format("0x%016X", address))
            .setView(input)
            .setPositiveButton(R.string.lemon_cheater_write) { _, _ ->
                val newValue = input.text?.toString()?.toIntOrNull()
                if (newValue == null) {
                    return@setPositiveButton
                }
                runBusy {
                    val ok = withContext(Dispatchers.Default) {
                        NativeLibrary.cheatWrite(address, intToLeBytes(newValue))
                    }
                    if (ok) {
                        // We just wrote this value ourselves - update it in place instead of
                        // re-reading, so the row reflects it immediately.
                        lastMatches = lastMatches.map {
                            if (it.address == address) it.copy(value = newValue) else it
                        }
                    } else {
                        Toast.makeText(
                            context,
                            R.string.lemon_cheater_write_failure,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    refreshResults()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun intToLeBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    /** Decodes the [addr0, value0, addr1, value1, ...] interleaved format the native side
     *  uses to return address+value pairs in a single long[] (see native.cpp). */
    private fun decodeMatches(interleaved: LongArray): List<Match> {
        val matches = ArrayList<Match>(interleaved.size / 2)
        var i = 0
        while (i + 1 < interleaved.size) {
            matches.add(Match(interleaved[i], interleaved[i + 1].toInt()))
            i += 2
        }
        return matches
    }

    private fun encodeMatches(matches: List<Match>): LongArray {
        val interleaved = LongArray(matches.size * 2)
        matches.forEachIndexed { index, match ->
            interleaved[index * 2] = match.address
            interleaved[index * 2 + 1] = match.value.toLong()
        }
        return interleaved
    }

    private enum class Comparison(val nativeValue: Int) {
        INCREASED(0),
        DECREASED(1),
        UNCHANGED(2),
    }

    companion object {
        private const val DISPLAY_LIMIT = 100

        // Must match native.cpp's cheatRefine: negative comparison means "exact value refine
        // using needleValue" rather than a increased/decreased comparison.
        private const val EXACT_COMPARISON = -1
    }
}
