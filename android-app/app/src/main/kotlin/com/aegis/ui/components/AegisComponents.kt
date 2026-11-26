package com.aegis.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.core.overlay.HudGlowMode
import com.aegis.core.overlay.HudSnapshot
import com.aegis.ui.theme.AegisTokens

@Composable
fun CleanCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = AegisTokens.Surface,
    borderColor: Color = AegisTokens.BorderSubtle,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun AegisTopBar(
    subtitle: String,
    isIsolatedOnline: Boolean,
    onStatusClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Lumen",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                color = AegisTokens.TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = AegisTokens.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(AegisTokens.Surface)
                .border(1.dp, AegisTokens.BorderSubtle, CircleShape)
                .clickable(onClick = onStatusClick)
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .semantics { contentDescription = "Offline sandbox status" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isIsolatedOnline) AegisTokens.TrustGreen else AegisTokens.WarnAmber)
            )
            Text(
                text = if (isIsolatedOnline) "Offline" else "Standby",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                color = AegisTokens.TextPrimary
            )
        }
    }
}

@Composable
fun SubtlePillTag(
    label: String,
    color: Color = AegisTokens.TextSecondary,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            color = color
        )
    }
}

private data class BottomNavTab(
    val label: String,
    val icon: ImageVector
)

@Composable
fun CleanBottomNavBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val tabs = listOf(
        BottomNavTab("Ask", Icons.Rounded.AutoAwesome),
        BottomNavTab("Activity", Icons.Rounded.History),
        BottomNavTab("Safety", Icons.Rounded.Shield),
        BottomNavTab("Device", Icons.Rounded.Memory)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AegisTokens.Canvas)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AegisTokens.Divider)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { idx, tab ->
                val active = idx == selectedTab
                val fg by animateColorAsState(
                    targetValue = if (active) AegisTokens.TextPrimary else AegisTokens.TextMuted,
                    animationSpec = tween(150),
                    label = "tabColor"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = AegisTokens.MinTouchTarget)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectTab(idx) }
                        .padding(vertical = 4.dp)
                        .semantics { contentDescription = "${tab.label} tab" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = fg,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun CleanRamBar(
    usedMb: Int,
    safeCapMb: Int,
    thermalCelsius: Int
) {
    val targetRatio = (usedMb.toFloat() / safeCapMb.toFloat()).coerceIn(0.03f, 1f)
    val ratio by animateFloatAsState(targetValue = targetRatio, animationSpec = tween(300), label = "ram")
    val barColor = when {
        ratio > 0.85f -> AegisTokens.DangerRed
        ratio > 0.65f -> AegisTokens.WarnAmber
        else -> AegisTokens.TrustGreen
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$usedMb MB of $safeCapMb MB",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = AegisTokens.TextPrimary
            )
            Text(
                text = "${thermalCelsius}°C",
                fontSize = 13.sp,
                color = AegisTokens.TextSecondary
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(AegisTokens.SurfaceRaised)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

/**
 * In-app Jetpack Compose Edge Glyph Glow halo mirroring [AegisLiveHudState] around the 4 screen edges.
 * Glows vibrantly with rotating cyan-violet-emerald gradients whenever SmolVLM-500M vision runs.
 */
@Composable
fun AegisEdgeGlyphComposeOverlay(hud: HudSnapshot) {
    if (hud.mode == HudGlowMode.IDLE) return
    val isVlm = hud.mode == HudGlowMode.VLM_GLOW
    val isBlocked = hud.mode == HudGlowMode.BLOCKED

    val pulse by animateFloatAsState(
        targetValue = if (isVlm) 1.0f else 0.7f,
        animationSpec = tween(durationMillis = 360),
        label = "glyphPulse"
    )

    val colors = when {
        isVlm -> listOf(
            AegisTokens.GlyphCyan,
            AegisTokens.GlyphIndigo,
            AegisTokens.GlyphViolet,
            AegisTokens.TrustGreen
        )
        isBlocked -> listOf(AegisTokens.DangerRed, AegisTokens.WarnAmber)
        else -> listOf(AegisTokens.TrustGreen, AegisTokens.GlyphCyan)
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val edgePx = if (isVlm) (8.dp.toPx() * pulse) else 4.dp.toPx()
        val glowPx = if (isVlm) (18.dp.toPx() * pulse) else 8.dp.toPx()
        val brush = Brush.linearGradient(colors = colors, start = Offset.Zero, end = Offset(size.width, size.height))
        val coreAlpha = if (isVlm) 0.90f else 0.60f
        val haloAlpha = if (isVlm) 0.24f * pulse else 0.10f

        // 4 straight edge strips + soft inner halo (avoids GPU SW ClipStack rounded-stroke masks)
        drawRect(brush = brush, topLeft = Offset.Zero, size = Size(size.width, glowPx), alpha = haloAlpha)
        drawRect(brush = brush, topLeft = Offset(0f, size.height - glowPx), size = Size(size.width, glowPx), alpha = haloAlpha)
        drawRect(brush = brush, topLeft = Offset.Zero, size = Size(glowPx, size.height), alpha = haloAlpha)
        drawRect(brush = brush, topLeft = Offset(size.width - glowPx, 0f), size = Size(glowPx, size.height), alpha = haloAlpha)

        drawRect(brush = brush, topLeft = Offset.Zero, size = Size(size.width, edgePx), alpha = coreAlpha)
        drawRect(brush = brush, topLeft = Offset(0f, size.height - edgePx), size = Size(size.width, edgePx), alpha = coreAlpha)
        drawRect(brush = brush, topLeft = Offset.Zero, size = Size(edgePx, size.height), alpha = coreAlpha)
        drawRect(brush = brush, topLeft = Offset(size.width - edgePx, 0f), size = Size(edgePx, size.height), alpha = coreAlpha)
    }
}

/**
 * Compact docked bottom ticker displaying live VLM inference status, step counter, and breadcrumb trail.
 */
@Composable
fun AegisLiveStepBottomBar(hud: HudSnapshot, modifier: Modifier = Modifier) {
    if (hud.mode == HudGlowMode.IDLE) return
    val accent = when (hud.mode) {
        HudGlowMode.VLM_GLOW -> AegisTokens.GlyphCyan
        HudGlowMode.BLOCKED -> AegisTokens.DangerRed
        else -> AegisTokens.TrustGreen
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AegisTokens.SurfaceGlass, shape)
            .border(1.dp, accent.copy(alpha = if (hud.vlmActive) 0.72f else 0.42f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(accent, CircleShape)
                )
                Text(
                    text = hud.statusTitle.ifBlank { if (hud.vlmActive) "SmolVLM-500M · Vision Glow" else "Lumen Agent" },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val badge = buildString {
                if (hud.totalSteps > 0) append("Step ${hud.currentStep}/${hud.totalSteps}")
                else if (hud.currentStep > 0) append("Step ${hud.currentStep}")
                if (hud.memoryLabel.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(hud.memoryLabel)
                }
            }
            if (badge.isNotBlank()) {
                Text(badge, fontSize = 11.sp, color = AegisTokens.TextSecondary, maxLines = 1)
            }
        }
        Text(
            text = hud.actionDetail.ifBlank { "Running verified on-device step..." },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AegisTokens.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (hud.completedSteps.isNotEmpty()) {
            Text(
                text = hud.completedSteps.joinToString(" › "),
                fontSize = 11.sp,
                color = AegisTokens.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
