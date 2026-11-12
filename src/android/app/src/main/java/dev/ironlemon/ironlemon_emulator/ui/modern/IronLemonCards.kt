// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.ui.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import dev.ironlemon.ironlemon_emulator.model.HomeViewModel

/**
 * A screen of the home navigation drawn in Compose: the shared-axis transitions, the home
 * navigation hidden, and [Content] filling the fragment. [goBack] leaves the screen.
 */
abstract class IronLemonComposeFragment : Fragment() {
    protected val homeViewModel: HomeViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { Content() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setNavigationVisibility(visible = false, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = false)
    }

    protected fun goBack() {
        view?.findNavController()?.popBackStack()
    }

    @Composable
    protected abstract fun Content()
}

/** Cards in as many columns as fit, with room at the bottom for floating buttons if [bottom]. */
@Composable
fun IronLemonCardGrid(
    modifier: Modifier = Modifier,
    minCardWidth: Dp = 420.dp,
    bottom: Dp = 24.dp,
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minCardWidth),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize(),
        content = content
    )
}

/**
 * A card with an optional icon, a title and a description. Tappable as a whole when [onClick] is
 * set; [trailing] holds its own buttons, at the end.
 */
@Composable
fun IronLemonActionCard(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.lemonInteractive(
                        shape = shape,
                        cornerRadius = 24.dp,
                        selected = selected,
                        focusScale = 1.02f,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(IronLemonColors.Surface)
            .border(1.dp, IronLemonColors.Outline, shape)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(IronLemonColors.Lemon),
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = IronLemonType.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!description.isNullOrBlank()) {
                BasicText(
                    description,
                    style = IronLemonType.Body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}
