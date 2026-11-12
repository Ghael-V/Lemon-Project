// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.MathContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ironlemon.ironlemon_emulator.NativeLibrary
import dev.ironlemon.ironlemon_emulator.R
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonButtonRow
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonColors
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonIconButton
import dev.ironlemon.ironlemon_emulator.ui.modern.IronLemonType
import dev.ironlemon.ironlemon_emulator.ui.modern.lemonInteractive

/**
 * "Lemon Cheater": a live memory search/edit panel that floats above the emulation surface.
 *
 * Unlike [InputOverlay], this doesn't cover the whole screen with a single hit-tested View -
 * it's a plain FrameLayout holding one small, draggable card. Touches outside the card's
 * bounds are never claimed here, so they fall through to whatever's beneath (the game/input
 * overlay) via ordinary Android view dispatch - no custom routing needed for that part.
 *
 * Values are 4 bytes, searched as signed integers or as floats (see [ValueType]). Native code
 * always hands back the raw 32 bits; this class decides how to show and edit them.
 */
class CheatOverlay(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private data class Match(val address: Long, val value: Int)

    // What the user typed, turned into what the native search wants: the raw bits and, for
    // floats, how far a stored value may be from it and still count as a match.
    private data class Needle(val bits: Int, val tolerance: Float)

    private enum class ValueType(val nativeValue: Int) {
        INT(0),
        FLOAT(1),
    }

    // What the panel shows, drawn by [CheatPanel].
    private var inputText by mutableStateOf("")
    private var inputError by mutableStateOf<Int?>(null)
    private var statusText by mutableStateOf("")
    private var shownRows by mutableStateOf<List<Pair<Long, Int>>>(emptyList())
    private var frozenKeys by mutableStateOf<Set<Long>>(emptySet())
    private var frozenCount by mutableIntStateOf(0)
    private var actionsEnabled by mutableStateOf(true)
    private var shownType by mutableStateOf(ValueType.INT)
    private var shownViewingFrozen by mutableStateOf(false)

    // The value being edited in the panel (its address), or null.
    private var editAddress by mutableStateOf<Long?>(null)
    private var editText by mutableStateOf("")
    private var editAsFloat = false

    private val panel: ComposeView

    private var lastMatches: List<Match> = emptyList()

    // How the candidate list in [lastMatches] is interpreted - set by cheat_type_toggle_group.
    private var valueType = ValueType.INT

    // Addresses that were frozen as floats, so the Frozen view keeps showing them as floats
    // even after the type selector is moved. Anything not in here is shown as an integer.
    private val frozenFloatAddresses = mutableSetOf<Long>()

    // Address -> value currently being held by the native freeze thread. Doubles as the "is
    // this address frozen" check (frozenValues.containsKey(...)) for both the results view
    // (so an already-frozen address still shows a locked icon there) and the dedicated
    // "Frozen" view below.
    private var frozenValues: MutableMap<Long, Int> = mutableMapOf()

    // Which list cheat_results_list is currently showing - toggled by cheat_view_toggle_group.
    private var viewingFrozen = false

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
        panel = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { CheatPanel() }
        }
        addView(
            panel,
            // Centered: pinned to the top-left it sat right behind the in-game menu that opens
            // from that same side. It is draggable afterward.
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        // Picks up any address still frozen from before this panel instance existed (e.g. the
        // activity/overlay was recreated while a game kept running), so its row shows a locked
        // icon immediately instead of only after the user visits the Frozen tab once.
        reloadFrozenThenDisplay()
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

    private fun dragBy(dx: Float, dy: Float) {
        panel.translationX += dx
        panel.translationY += dy
    }

    /** The same candidate bits mean different things as an int and as a float, so a type change
     *  starts over (a blind-search snapshot is raw memory and stays valid). */
    private fun onValueTypeChanged(newType: ValueType) {
        valueType = newType
        shownType = newType
        inputError = null
        val hadResults = lastMatches.isNotEmpty()
        lastMatches = emptyList()
        setButtonsEnabled(enabled = !isBusy)
        updateDisplay()
        if (hadResults) {
            statusText = context.getString(R.string.lemon_cheater_type_changed)
        }
    }

    private fun parseValueOrShowError(): Needle? {
        val text = inputText
        val needle = if (valueType == ValueType.FLOAT) parseFloatNeedle(text) else {
            text.toIntOrNull()?.let { Needle(it, 0f) }
        }
        if (needle == null) {
            inputError = if (valueType == ValueType.FLOAT) {
                R.string.lemon_cheater_invalid_float
            } else {
                R.string.lemon_cheater_invalid_value
            }
        }
        return needle
    }

    /** A game seldom stores exactly what the user typed, or shows it rounded. Typing "87.3"
     *  accepts 87.25 up to 87.35 (half a unit of the last typed digit); "100" accepts 99.5 up to
     *  100.5. */
    private fun parseFloatNeedle(text: String): Needle? {
        val normalized = text.trim().replace(',', '.')
        val value = normalized.toFloatOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        val decimals = normalized.substringAfter('.', "").takeWhile { it.isDigit() }.length
        val tolerance = 0.5f * 10f.pow(-decimals)
        return Needle(value.toRawBits(), tolerance)
    }

    /** Raw 32 bits as the text the user sees for [address]: a float if that is how the address
     *  was found (or frozen), a plain integer otherwise. */
    private fun formatValue(address: Long, bits: Int): String {
        val asFloat = if (viewingFrozen) address in frozenFloatAddresses else valueType == ValueType.FLOAT
        return if (asFloat) formatFloat(bits) else bits.toString()
    }

    private fun formatFloat(bits: Int): String {
        val value = Float.fromBits(bits)
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == 0f) return "0"
        val magnitude = abs(value)
        return if (magnitude >= 1e7f || magnitude < 1e-4f) {
            String.format(Locale.ROOT, "%.4e", value)
        } else {
            BigDecimal(value.toDouble()).round(MathContext(7)).stripTrailingZeros().toPlainString()
        }
    }

    private fun onSearchClicked() {
        if (isBusy) return
        val needle = parseValueOrShowError() ?: return
        val type = valueType
        runBusy {
            val results = withContext(Dispatchers.Default) {
                NativeLibrary.cheatSearch(needle.bits, type.nativeValue, needle.tolerance)
            }
            awaitingBlindCompare = false
            lastMatches = decodeMatches(results)
            showResultsView()
        }
    }

    private fun onRefineClicked() {
        if (isBusy) return
        val needle = parseValueOrShowError() ?: return
        val type = valueType
        runBusy {
            val candidates = encodeMatches(lastMatches)
            val results = withContext(Dispatchers.Default) {
                NativeLibrary.cheatRefine(
                    candidates,
                    EXACT_COMPARISON,
                    needle.bits,
                    type.nativeValue,
                    needle.tolerance
                )
            }
            lastMatches = decodeMatches(results)
            showResultsView()
        }
    }

    private fun onBlindSearchClicked() {
        if (isBusy) return
        runBusy {
            withContext(Dispatchers.Default) { NativeLibrary.cheatTakeSnapshot() }
            awaitingBlindCompare = true
            lastMatches = emptyList()
            showResultsView()
            statusText = context.getString(R.string.lemon_cheater_snapshot_taken)
        }
    }

    private fun onComparisonClicked(comparison: Comparison) {
        if (isBusy) return
        val type = valueType
        runBusy {
            val results = withContext(Dispatchers.Default) {
                if (awaitingBlindCompare) {
                    NativeLibrary.cheatCompareSnapshot(comparison.nativeValue, type.nativeValue)
                } else {
                    NativeLibrary.cheatRefine(
                        encodeMatches(lastMatches),
                        comparison.nativeValue,
                        0,
                        type.nativeValue,
                        0f
                    )
                }
            }
            awaitingBlindCompare = false
            lastMatches = decodeMatches(results)
            showResultsView()
        }
    }

    /** Switches back to the "Results" view (e.g. after a new search/refine) and redisplays. */
    private fun showResultsView() {
        viewingFrozen = false
        shownViewingFrozen = false
        updateDisplay()
    }

    /** Runs [block] with every action button disabled and a "working..." status. */
    private fun runBusy(block: suspend () -> Unit) {
        isBusy = true
        setButtonsEnabled(enabled = false)
        statusText = context.getString(R.string.lemon_cheater_searching)

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
        actionsEnabled = enabled
    }

    private val canRefine get() = actionsEnabled && lastMatches.isNotEmpty()
    private val canCompare get() = actionsEnabled && (awaitingBlindCompare || lastMatches.isNotEmpty())

    /** Updates the results list and status text from whichever of [lastMatches]/[frozenValues]
     *  is currently selected (see [viewingFrozen]) - no native calls needed, every path that
     *  sets those already has each address' current value on hand. Also keeps the "Frozen (N)"
     *  tab label in sync. */
    private fun updateDisplay() {
        val rows = if (viewingFrozen) {
            frozenValues.entries.sortedBy { it.key }.map { it.key to it.value }
        } else {
            lastMatches.take(DISPLAY_LIMIT).map { it.address to it.value }
        }
        shownRows = rows
        frozenKeys = frozenValues.keys.toSet()
        frozenCount = frozenValues.size

        statusText = if (viewingFrozen) {
            if (rows.isEmpty()) {
                context.getString(R.string.lemon_cheater_no_frozen)
            } else {
                context.getString(R.string.lemon_cheater_results_count, rows.size)
            }
        } else {
            val total = lastMatches.size
            when {
                total == 0 -> context.getString(R.string.lemon_cheater_no_results)
                total > DISPLAY_LIMIT -> context.getString(
                    R.string.lemon_cheater_showing_subset,
                    rows.size,
                    total
                )
                else -> context.getString(R.string.lemon_cheater_results_count, total)
            }
        }
    }

    /** Re-fetches the frozen address set from native, then redisplays whichever view is
     *  currently selected (icons on the Results view also depend on [frozenValues]). */
    private fun reloadFrozenThenDisplay() {
        scope.launch {
            val raw = withContext(Dispatchers.Default) { NativeLibrary.cheatGetFrozen() }
            frozenValues = decodeMatches(raw).associate { it.address to it.value }.toMutableMap()
            updateDisplay()
        }
    }

    private fun onFreezeToggled(address: Long, value: Int, currentlyFrozen: Boolean) {
        scope.launch {
            withContext(Dispatchers.Default) {
                if (currentlyFrozen) {
                    NativeLibrary.cheatClearFrozen(address)
                } else {
                    NativeLibrary.cheatSetFrozen(address, value)
                }
            }
            if (currentlyFrozen) {
                frozenValues.remove(address)
                frozenFloatAddresses.remove(address)
            } else {
                frozenValues[address] = value
                if (valueType == ValueType.FLOAT) frozenFloatAddresses.add(address)
            }
            updateDisplay()
        }
    }

    private fun startEdit(address: Long, currentValue: Int) {
        editAsFloat = if (viewingFrozen) address in frozenFloatAddresses else valueType == ValueType.FLOAT
        editText = if (editAsFloat) formatFloat(currentValue) else currentValue.toString()
        editAddress = address
    }

    private fun confirmEdit() {
        val address = editAddress ?: return
        val typed = editText
        val newValue = if (editAsFloat) {
            typed.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }?.toRawBits()
        } else {
            typed.toIntOrNull()
        } ?: return
        editAddress = null
        val isFrozen = frozenValues.containsKey(address)
        runBusy {
            // A frozen address gets rewritten by the native freeze thread roughly every 100ms:
            // writing through cheatWrite() here would be overwritten by the old frozen value on
            // the next tick. Update the frozen target itself instead, so this edit is what stays.
            val ok = withContext(Dispatchers.Default) {
                if (isFrozen) {
                    NativeLibrary.cheatSetFrozen(address, newValue)
                    true
                } else {
                    NativeLibrary.cheatWrite(address, intToLeBytes(newValue))
                }
            }
            if (ok) {
                if (isFrozen) {
                    frozenValues[address] = newValue
                }
                // Written by us: update it in place instead of reading it back.
                lastMatches = lastMatches.map {
                    if (it.address == address) it.copy(value = newValue) else it
                }
            } else {
                Toast.makeText(context, R.string.lemon_cheater_write_failure, Toast.LENGTH_SHORT)
                    .show()
            }
            updateDisplay()
        }
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

    @Composable
    private fun CheatPanel() {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .width(420.dp)
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(IronLemonColors.Background.copy(alpha = 0.96f))
                .border(1.dp, IronLemonColors.gradient(0.6f), shape)
        ) {
            // The title bar drags the panel around.
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            dragBy(amount.x, amount.y)
                        }
                    }
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    stringResource(R.string.lemon_cheater),
                    style = IronLemonType.Heading,
                    modifier = Modifier.weight(1f)
                )
                IronLemonIconButton(
                    iconRes = R.drawable.ic_clear,
                    description = stringResource(R.string.emulation_done),
                    size = 40.dp,
                    onClick = ::hide
                )
            }
            val address = editAddress
            if (address != null) {
                EditPage(address)
            } else {
                SearchPage()
            }
        }
    }

    @Composable
    private fun SearchPage() {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.lemon_cheater_type_int), shownType == ValueType.INT) {
                    if (valueType != ValueType.INT) onValueTypeChanged(ValueType.INT)
                }
                Chip(stringResource(R.string.lemon_cheater_type_float), shownType == ValueType.FLOAT) {
                    if (valueType != ValueType.FLOAT) onValueTypeChanged(ValueType.FLOAT)
                }
            }
            Spacer(Modifier.height(10.dp))
            ValueField(
                value = inputText,
                hint = stringResource(R.string.lemon_cheater_value_hint),
                float = shownType == ValueType.FLOAT,
                onChange = {
                    inputText = it
                    inputError = null
                }
            )
            inputError?.let {
                BasicText(
                    stringResource(it),
                    style = IronLemonType.Body.copy(color = IronLemonColors.Red),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            IronLemonButtonRow {
                SmallButton(stringResource(R.string.lemon_cheater_new_search), true, actionsEnabled) {
                    onSearchClicked()
                }
                SmallButton(stringResource(R.string.lemon_cheater_refine), false, canRefine) {
                    onRefineClicked()
                }
                SmallButton(stringResource(R.string.lemon_cheater_blind_search), false, actionsEnabled) {
                    onBlindSearchClicked()
                }
            }
            Spacer(Modifier.height(8.dp))
            IronLemonButtonRow {
                SmallButton(stringResource(R.string.lemon_cheater_increased), false, canCompare) {
                    onComparisonClicked(Comparison.INCREASED)
                }
                SmallButton(stringResource(R.string.lemon_cheater_decreased), false, canCompare) {
                    onComparisonClicked(Comparison.DECREASED)
                }
                SmallButton(stringResource(R.string.lemon_cheater_unchanged), false, canCompare) {
                    onComparisonClicked(Comparison.UNCHANGED)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.lemon_cheater_view_results), !shownViewingFrozen) {
                    viewingFrozen = false
                    shownViewingFrozen = false
                    updateDisplay()
                }
                Chip(stringResource(R.string.lemon_cheater_view_frozen, frozenCount), shownViewingFrozen) {
                    viewingFrozen = true
                    shownViewingFrozen = true
                    reloadFrozenThenDisplay()
                }
            }
            Spacer(Modifier.height(8.dp))
            BasicText(statusText, style = IronLemonType.Body)
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(shownRows, key = { it.first }) { (rowAddress, value) ->
                    ResultRow(rowAddress, value, rowAddress in frozenKeys)
                }
            }
        }
    }

    @Composable
    private fun ResultRow(address: Long, value: Int, frozen: Boolean) {
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .lemonInteractive(
                    shape = shape,
                    cornerRadius = 12.dp,
                    focusScale = 1.02f,
                    onClick = { startEdit(address, value) }
                )
                .clip(shape)
                .background(IronLemonColors.Surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                String.format(Locale.ROOT, "0x%016X", address),
                style = IronLemonType.Body.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.weight(1f)
            )
            BasicText(
                formatValue(address, value),
                style = IronLemonType.Button.copy(color = IronLemonColors.Lemon)
            )
            Spacer(Modifier.width(8.dp))
            IronLemonIconButton(
                iconRes = if (frozen) R.drawable.ic_lock else R.drawable.ic_unlock,
                description = stringResource(
                    if (frozen) R.string.lemon_cheater_unfreeze else R.string.lemon_cheater_freeze
                ),
                size = 36.dp,
                onClick = { onFreezeToggled(address, value, frozen) }
            )
        }
    }

    @Composable
    private fun EditPage(address: Long) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            BasicText(
                String.format(Locale.ROOT, "0x%016X", address),
                style = IronLemonType.Label
            )
            Spacer(Modifier.height(10.dp))
            ValueField(value = editText, hint = "", float = editAsFloat, onChange = { editText = it })
            Spacer(Modifier.height(12.dp))
            IronLemonButtonRow {
                SmallButton(stringResource(android.R.string.cancel), false, true) { editAddress = null }
                SmallButton(stringResource(R.string.lemon_cheater_write), true, true) { confirmEdit() }
            }
        }
    }

    @Composable
    private fun ValueField(value: String, hint: String, float: Boolean, onChange: (String) -> Unit) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(IronLemonColors.Surface)
                .border(1.dp, IronLemonColors.Outline, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (value.isEmpty() && hint.isNotEmpty()) {
                BasicText(hint, style = IronLemonType.Body)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = IronLemonType.Button,
                cursorBrush = SolidColor(IronLemonColors.Lemon),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (float) KeyboardType.Decimal else KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .lemonInteractive(shape = shape, cornerRadius = 16.dp, selected = selected, onClick = onClick)
                .clip(shape)
                .then(
                    if (selected) {
                        Modifier.background(IronLemonColors.Gradient)
                    } else {
                        Modifier.background(IronLemonColors.SurfaceRaised)
                    }
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            BasicText(
                text,
                style = IronLemonType.Button.copy(
                    color = if (selected) IronLemonColors.OnLemon else IronLemonColors.Text,
                    fontSize = 14.sp
                )
            )
        }
    }

    @Composable
    private fun SmallButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
        IronLemonButton(
            text = text,
            primary = primary && enabled,
            height = 40.dp,
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
            onClick = { if (enabled) onClick() }
        )
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
