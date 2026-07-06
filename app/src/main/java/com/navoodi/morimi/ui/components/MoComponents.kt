package com.navoodi.morimi.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navoodi.morimi.ui.theme.MoColors
import com.navoodi.morimi.ui.theme.MoMotion
import com.navoodi.morimi.ui.theme.MoRadius
import com.navoodi.morimi.ui.theme.MoSpacing
import com.navoodi.morimi.ui.theme.moCardSurface

// ─────────────────────────────────────────────────────────────
// MoButton
// ─────────────────────────────────────────────────────────────
enum class MoButtonVariant { Primary, Secondary, Ghost, Danger, Soft }
enum class MoButtonSize { Sm, Md, Lg }

private data class BtnSizeSpec(val height: Dp, val hPad: Dp, val fontSize: Int, val shape: Shape)

@Composable
fun MoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MoButtonVariant = MoButtonVariant.Primary,
    size: MoButtonSize = MoButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    fullWidth: Boolean = false,
) {
    val s = when (size) {
        MoButtonSize.Sm -> BtnSizeSpec(36.dp, 14.dp, 13, MoRadius.md)
        MoButtonSize.Md -> BtnSizeSpec(44.dp, 18.dp, 15, MoRadius.lg)
        MoButtonSize.Lg -> BtnSizeSpec(53.dp, 24.dp, 16, MoRadius.lg)
    }
    val (bg, fg, border) = when (variant) {
        MoButtonVariant.Primary   -> Triple(MoColors.brand, MoColors.textOnBrand, null)
        MoButtonVariant.Secondary -> Triple(Color.Transparent, MoColors.brand, MoColors.brand)
        MoButtonVariant.Ghost     -> Triple(Color.Transparent, MoColors.textPrimary, null)
        MoButtonVariant.Danger    -> Triple(MoColors.warningBg, MoColors.warningText, null)
        MoButtonVariant.Soft      -> Triple(MoColors.brandSubtle, MoColors.brand, null)
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !loading) MoMotion.pressScale else 1f,
        animationSpec = tween(120, easing = MoMotion.easeStandard),
        label = "btnScale",
    )
    val alpha = if (enabled && !loading) 1f else 0.38f

    Row(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(s.height)
            .scale(scale)
            .clip(s.shape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(if (border != null) Modifier.border(1.5.dp, border.copy(alpha = alpha), s.shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick,
            )
            .padding(horizontal = s.hPad),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = fg,
                strokeWidth = 2.dp,
            )
        } else {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = fg.copy(alpha = alpha),
                fontSize = s.fontSize.sp,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// MoCard
// ─────────────────────────────────────────────────────────────
@Composable
fun MoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    shape: Shape = MoRadius.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = tween(120, easing = MoMotion.easeStandard),
        label = "cardScale",
    )
    Column(
        modifier = modifier
            .scale(scale)
            .moCardSurface(shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            )
            .padding(padding),
        content = content,
    )
}

// ─────────────────────────────────────────────────────────────
// MoBadge / MoChip — 도메인 노드 색상
// ─────────────────────────────────────────────────────────────
enum class MoNodeType { Participant, Place, Item, Activity, Warn, Neutral, Brand }

private fun nodeColors(type: MoNodeType): Pair<Color, Color> = when (type) {
    MoNodeType.Participant -> MoColors.participant to MoColors.participantBg
    MoNodeType.Place       -> MoColors.place to MoColors.placeBg
    MoNodeType.Item        -> MoColors.item to MoColors.itemBg
    MoNodeType.Activity    -> MoColors.activity to MoColors.activityBg
    MoNodeType.Warn        -> MoColors.warn to MoColors.warnBg
    MoNodeType.Brand       -> MoColors.brand to MoColors.brandSubtle
    MoNodeType.Neutral     -> MoColors.textSecondary to MoColors.surfaceSubtle
}

@Composable
fun MoBadge(
    text: String,
    type: MoNodeType = MoNodeType.Brand,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
) {
    val (fg, bg) = nodeColors(type)
    Row(
        modifier = modifier
            .clip(MoRadius.full)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(
                Modifier.size(6.dp).clip(CircleShape).background(fg),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ─────────────────────────────────────────────────────────────
// MoTextField
// ─────────────────────────────────────────────────────────────
@Composable
fun MoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = MoColors.textTertiary) } },
        singleLine = singleLine,
        isError = isError,
        shape = MoRadius.md,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MoColors.brand,
            unfocusedBorderColor = MoColors.border,
            errorBorderColor = MoColors.warn,
            focusedContainerColor = MoColors.surfaceBase,
            unfocusedContainerColor = MoColors.surfaceBase,
            focusedLabelColor = MoColors.brand,
            unfocusedLabelColor = MoColors.textTertiary,
            cursorColor = MoColors.brand,
            focusedTextColor = MoColors.textPrimary,
            unfocusedTextColor = MoColors.textPrimary,
        ),
    )
}

// ─────────────────────────────────────────────────────────────
// MoStatusBadge — 플로팅 AI 상태 pill ("스피너 없음" 시그니처)
// ─────────────────────────────────────────────────────────────
enum class MoStatus { Analyzing, Syncing, Warning, Done }

@Composable
fun MoStatusBadge(
    status: MoStatus,
    text: String,
    modifier: Modifier = Modifier,
) {
    val dotColor = when (status) {
        MoStatus.Analyzing, MoStatus.Syncing -> MoColors.brand
        MoStatus.Warning -> MoColors.warn
        MoStatus.Done -> MoColors.place
    }
    val pulsing = status == MoStatus.Analyzing || status == MoStatus.Syncing || status == MoStatus.Warning
    val transition = rememberInfiniteTransition(label = "statusPulse")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(750, easing = MoMotion.easeStandard), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Row(
        modifier = modifier
            .clip(MoRadius.full)
            .background(MoColors.surfaceSubtle.copy(alpha = 0.95f))
            .border(1.dp, MoColors.border, MoRadius.full)
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dotAlpha)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = dotColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ─────────────────────────────────────────────────────────────
// MoTopBar — 42dp 툴바 패턴
// ─────────────────────────────────────────────────────────────
@Composable
fun MoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    navIcon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MoColors.surfaceBase)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        navIcon ?: Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "뒤로",
                        tint = MoColors.textPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            color = MoColors.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Row(
            modifier = Modifier.widthIn(min = 44.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}
