package org.yaop.ui.tablet

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.DefaultTypes
import org.yaop.ui.main.NavigationTab
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.AppMotion
import org.yaop.ui.theme.pressScale

/**
 * Desktop & Tablet Navigation Rail.
 * Placed along the left edge for ergonomic one-tap thumb and stylus access.
 */
@Composable
fun TabletNavigationRail(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    onCreateObject: (typeId: String) -> Unit,
    onQuickDrawing: () -> Unit,
    isDetailExpanded: Boolean,
    onToggleExpandDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(88.dp)
            .background(Color(0x14FFFFFF)) // Translucent rail: white 8% over the gradient
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top section: App Logo + "+ New" Create Action Button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // App Brand Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.SurfaceElevated)
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(org.yaop.R.drawable.ic_launcher_foreground),
                    contentDescription = "YAOP",
                    modifier = Modifier.size(44.dp),
                )
            }

            // Direct "+ New" Button with Dropdown
            Box {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(AppColors.Accent)
                        .pressScale()
                        .clickable { showCreateMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.tablet_nav_new),
                        tint = AppColors.AccentInk,
                        modifier = Modifier.size(28.dp),
                    )
                }

                DropdownMenu(
                    expanded = showCreateMenu,
                    onDismissRequest = { showCreateMenu = false },
                    modifier = Modifier
                        .background(AppColors.SurfaceElevated, RoundedCornerShape(16.dp))
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(16.dp)),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.quick_action_note), color = AppColors.TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Notes, null, tint = AppColors.Ice) },
                        onClick = {
                            showCreateMenu = false
                            onCreateObject(DefaultTypes.NOTE.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.quick_action_task), color = AppColors.TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null, tint = AppColors.Ice) },
                        onClick = {
                            showCreateMenu = false
                            onCreateObject(DefaultTypes.TASK.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tablet_nav_new_drawing), color = AppColors.TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Palette, null, tint = AppColors.Ice) },
                        onClick = {
                            showCreateMenu = false
                            onQuickDrawing()
                        },
                    )
                    HorizontalDivider(color = AppColors.BorderSubtle, modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.quick_action_link), color = AppColors.TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Link, null, tint = org.yaop.ui.theme.AppTypeColors.of("weblink")) },
                        onClick = {
                            showCreateMenu = false
                            onCreateObject(DefaultTypes.WEBLINK.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.quick_action_audio), color = AppColors.TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Mic, null, tint = AppColors.Ice) },
                        onClick = {
                            showCreateMenu = false
                            onCreateObject(DefaultTypes.AUDIO.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.quick_action_image), color = AppColors.TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Image, null, tint = org.yaop.ui.theme.AppTypeColors.of("image")) },
                        onClick = {
                            showCreateMenu = false
                            onCreateObject(DefaultTypes.IMAGE.id)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main Navigation Slots
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TabletRailTabItem(
                    icon = Icons.Default.Home,
                    label = stringResource(R.string.tablet_nav_home),
                    isSelected = currentTab == NavigationTab.HOME,
                    onClick = { onTabSelected(NavigationTab.HOME) },
                )
                TabletRailTabItem(
                    icon = Icons.Default.CalendarToday,
                    label = stringResource(R.string.tablet_nav_daily),
                    isSelected = currentTab == NavigationTab.DAILY,
                    onClick = { onTabSelected(NavigationTab.DAILY) },
                )
                TabletRailTabItem(
                    icon = Icons.Default.CheckCircle,
                    label = stringResource(R.string.tablet_nav_tasks),
                    isSelected = currentTab == NavigationTab.TASKS,
                    onClick = { onTabSelected(NavigationTab.TASKS) },
                )
                TabletRailTabItem(
                    icon = Icons.Default.Search,
                    label = stringResource(R.string.tablet_nav_search),
                    isSelected = currentTab == NavigationTab.SEARCH,
                    onClick = { onTabSelected(NavigationTab.SEARCH) },
                )
            }
        }

        // Bottom section: Quick Sketch (Pincel/Stylus) + Split/Focus toggle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Quick Drawing / Stylus Canvas Button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(AppColors.Surface)
                    .border(1.dp, AppColors.BorderSubtle, CircleShape)
                    .pressScale()
                    .clickable(onClick = onQuickDrawing),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Brush,
                    contentDescription = stringResource(R.string.tablet_quick_sketch),
                    tint = AppColors.Ice,
                    modifier = Modifier.size(22.dp),
                )
            }

        }
    }
}

@Composable
private fun TabletRailTabItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgAnim by animateColorAsState(
        targetValue = if (isSelected) Color(0x24FFFFFF) else Color.Transparent,
        animationSpec = AppMotion.gentle(),
        label = "RailTabBg",
    )
    val iconColorAnim by animateColorAsState(
        targetValue = if (isSelected) AppColors.Ice else AppColors.TextSecondary,
        animationSpec = AppMotion.snappy(),
        label = "RailTabIcon",
    )

    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgAnim, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColorAnim,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label.uppercase(),
            style = org.yaop.ui.theme.AppLabelStyle.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp),
            color = iconColorAnim,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
