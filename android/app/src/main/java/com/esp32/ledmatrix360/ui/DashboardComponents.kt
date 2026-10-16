@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.esp32.ledmatrix360.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esp32.ledmatrix360.ble.BleManager
import com.esp32.ledmatrix360.ble.sanitizeVietnameseForLed
import com.esp32.ledmatrix360.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ============================================================================
// 1. MÀN HÌNH CHÍNH 1: HIỆU ỨNG (STUDIO)
// ============================================================================

@Composable
fun StudioScreen(
    telemetry: TelemetryState,
    effectSpeed: Int,
    onBrightnessChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onPowerToggle: () -> Unit,
    onModeSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Card: Nguồn, Độ sáng & Tốc độ hiệu ứng M3
        BrightnessPowerHeroCard(
            brightness = telemetry.brightness,
            speed = effectSpeed,
            isStandby = telemetry.mode == 9,
            onBrightnessChange = onBrightnessChange,
            onSpeedChange = onSpeedChange,
            onPowerToggle = onPowerToggle
        )

        // Telemetry Tonal Chips (Pin, Góc nghiêng, Heap)
        TelemetryChipRow(state = telemetry)

        // Tiêu đề & Danh sách 13 Chế độ
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Thư viện hiệu ứng",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${LampConstants.MODES.size} chế độ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Grid 13 chế độ ánh sáng với Vector Icons chuẩn M3
        ModesGrid(
            currentMode = telemetry.mode,
            onModeSelect = onModeSelect
        )
    }
}

@Composable
fun BrightnessPowerHeroCard(
    brightness: Int,
    speed: Int,
    isStandby: Boolean,
    onBrightnessChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onPowerToggle: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var localBrightness by remember { mutableFloatStateOf(brightness.toFloat()) }
    var lastSentTime by remember { mutableLongStateOf(0L) }

    // Chỉ đồng bộ từ bên ngoài khi người dùng không đang trực tiếp kéo thanh trượt
    LaunchedEffect(brightness) {
        if (!isDragging) {
            localBrightness = brightness.toFloat()
        }
    }

    val currentDisplayBri = localBrightness.toInt()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (!isStandby) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (!isStandby) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                                contentDescription = null,
                                tint = if (!isStandby) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = if (isStandby) "Đèn đang chờ (Standby)" else "Ánh sáng chủ động",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Độ sáng: $currentDisplayBri/255 (${(currentDisplayBri * 100 / 255)}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Nút nguồn M3 FilledIconToggleButton
                FilledIconToggleButton(
                    checked = !isStandby,
                    onCheckedChange = { onPowerToggle() },
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = "Bật/Tắt nguồn",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Thanh trượt độ sáng M3 Slider (Mượt 120Hz không khựng)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.BrightnessLow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Slider(
                    value = localBrightness,
                    onValueChange = { newVal ->
                        isDragging = true
                        localBrightness = newVal
                        val now = System.currentTimeMillis()
                        if (now - lastSentTime > 100L) {
                            lastSentTime = now
                            onBrightnessChange(newVal.toInt())
                        }
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onBrightnessChange(localBrightness.toInt())
                    },
                    valueRange = 5f..255f,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.BrightnessHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // M3 Quick Chips độ sáng nhanh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Pair("25%", 64),
                    Pair("50%", 128),
                    Pair("75%", 192),
                    Pair("100%", 255)
                ).forEach { (label, value) ->
                    val isSelected = (currentDisplayBri in (value - 15)..(value + 15))
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            isDragging = false
                            localBrightness = value.toFloat()
                            onBrightnessChange(value)
                        },
                        label = {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }


            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Thanh điều chỉnh tốc độ chuyển động hiệu ứng
            var localSpeed by remember(speed) { mutableFloatStateOf(speed.toFloat()) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Tốc độ hiệu ứng",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${localSpeed.toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Slider(
                    value = localSpeed,
                    onValueChange = { localSpeed = it },
                    onValueChangeFinished = { onSpeedChange(localSpeed.toInt()) },
                    valueRange = 10f..100f
                )
            }
        }
    }
}

@Composable
fun TelemetryChipRow(state: TelemetryState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Pin & Nguồn
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "Pin và nguồn: ${when (state.powerState) { 1 -> "Đang sạc"; 2 -> "Pin đầy"; else -> "Dùng pin" }}, điện áp ${state.statMv} millivolt"
                }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when {
                        state.powerState == 1 -> Icons.Filled.BatteryChargingFull
                        state.powerState == 2 -> Icons.Filled.BatteryFull
                        state.vbus == 1 -> Icons.Filled.Power
                        else -> Icons.Filled.BatteryStd
                    },
                    contentDescription = null,
                    tint = when {
                        state.powerState == 1 -> AmberWarm
                        state.powerState == 2 -> GreenJade
                        state.vbus == 1 -> AmberWarm
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = when {
                            state.powerState == 1 -> "Đang sạc (USB)"
                            state.powerState == 2 -> "Đã sạc đầy"
                            state.vbus == 1 -> "Đang cắm nguồn"
                            else -> "Dùng pin"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (state.statMv > 0) "${state.statMv} mV" else if (state.vbus == 1) "5.0V USB" else "Pin Li-ion",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Cảm biến nghiêng MPU
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "Cảm biến nghiêng MPU-6050, pitch ${state.pitch.toInt()} độ, roll ${state.roll.toInt()} độ"
                }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ScreenRotation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "MPU-6050",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.pitch.toInt()}° / ${state.roll.toInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // RAM Heap
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "Bộ nhớ RAM còn trống ${state.heap / 1024} kilobyte"
                }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "Bộ nhớ RAM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.heap / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ModesGrid(
    currentMode: Int,
    onModeSelect: (Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 840.dp -> 4
            maxWidth >= 600.dp -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LampConstants.MODES.chunked(columns).forEach { rowModes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowModes.forEach { mode ->
                        val isSelected = mode.id == currentMode
                        ModeCard(
                            mode = mode,
                            isSelected = isSelected,
                            onClick = { onModeSelect(mode.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowModes.size < columns) {
                        repeat(columns - rowModes.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

fun getModeVectorIcon(modeId: Int): ImageVector {
    return when (modeId) {
        0 -> Icons.Filled.Autorenew
        1 -> Icons.Filled.Favorite
        2 -> Icons.Filled.AutoAwesome
        3 -> Icons.Filled.WbIncandescent
        4 -> Icons.Filled.ScreenRotation
        5 -> Icons.Filled.LocalFireDepartment
        6 -> Icons.Filled.Spa
        7 -> Icons.Filled.BlurOn
        8 -> Icons.Filled.Star
        9 -> Icons.Filled.PowerSettingsNew
        10 -> Icons.Filled.ColorLens
        11 -> Icons.Filled.GridOn
        12 -> Icons.Filled.LocalFlorist
        else -> Icons.Filled.Lightbulb
    }
}

@Composable
fun ModeCard(
    mode: LampMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.semantics {
            selected = isSelected
            role = Role.RadioButton
        },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = getModeVectorIcon(mode.id),
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Đang chọn",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = mode.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================================
// 2. MÀN HÌNH CHÍNH 2: SÁNG TẠO (CREATIVE)
// ============================================================================

@Composable
fun CreativeScreen(
    initialMarqueeText: String,
    onColorSelected: (Int, Int, Int) -> Unit,
    onSendMarqueeText: (String, Int?, Int?, Int?, Int) -> Unit,
    onSendSlot: (slot: Int, r: Int, g: Int, b: Int, rows: ByteArray) -> Unit,
    onSendAllSlots: (slots: List<SlotBitmapState>) -> Unit,
    onSendFullCanvas: (PixelCanvasState) -> Unit,
    onActivateMode11: () -> Unit
) {
    var selectedCreativeTab by remember { mutableIntStateOf(0) } // 0: Palette, 1: Marquee, 2: Pixel Art

    Column(modifier = Modifier.fillMaxSize()) {
        // M3 Secondary Tab Row
        SecondaryTabRow(
            selectedTabIndex = selectedCreativeTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedCreativeTab == 0,
                onClick = { selectedCreativeTab = 0 },
                text = { Text("Bảng màu RGB") },
                icon = { Icon(Icons.Filled.ColorLens, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
            Tab(
                selected = selectedCreativeTab == 1,
                onClick = { selectedCreativeTab = 1 },
                text = { Text("Chữ 360°") },
                icon = { Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
            Tab(
                selected = selectedCreativeTab == 2,
                onClick = { selectedCreativeTab = 2 },
                text = { Text("Pixel Art") },
                icon = { Icon(Icons.Filled.Brush, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when (selectedCreativeTab) {
                0 -> ColorPaletteSection(onColorSelected = onColorSelected)
                1 -> MarqueeSection(
                    initialText = initialMarqueeText,
                    onSendText = onSendMarqueeText
                )
                2 -> PixelArtCanvasSection(
                    onSendSlot = onSendSlot,
                    onSendAllSlots = onSendAllSlots,
                    onSendFullCanvas = onSendFullCanvas,
                    onActivateMode11 = onActivateMode11
                )
            }
        }
    }
}

@Composable
fun ColorPaletteSection(
    onColorSelected: (Int, Int, Int) -> Unit
) {
    var hue by remember { mutableFloatStateOf(180f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var valueBri by remember { mutableFloatStateOf(1f) }

    val currentColor = remember(hue, saturation, valueBri) {
        val hsv = floatArrayOf(hue, saturation, valueBri)
        val androidCol = android.graphics.Color.HSVToColor(hsv)
        Color(androidCol)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Color Preview Hero Card
        ElevatedCard(
            onClick = {
                val r = (currentColor.red * 255).toInt()
                val g = (currentColor.green * 255).toInt()
                val b = (currentColor.blue * 255).toInt()
                onColorSelected(r, g, b)
            },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 110.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = currentColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                val isLight = (0.299 * currentColor.red + 0.587 * currentColor.green + 0.114 * currentColor.blue) > 0.5
                val textColor = if (isLight) Color.Black else Color.White

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (isLight) Color.White else Color.Black).copy(alpha = 0.4f),
                    modifier = Modifier.padding(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "RGB(${(currentColor.red * 255).toInt()}, ${(currentColor.green * 255).toInt()}, ${(currentColor.blue * 255).toInt()})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Text(
                            text = "• Chạm để áp dụng",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor
                        )
                    }
                }
            }
        }

        // Hue Slider
        Text(
            text = "Dải sắc độ (Hue: ${hue.toInt()}°)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Red, Color.Yellow, Color.Green,
                            Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                        )
                    )
                )
        )

        var lastSentTime by remember { mutableLongStateOf(0L) }
        Slider(
            value = hue,
            onValueChange = {
                hue = it
                val now = System.currentTimeMillis()
                if (now - lastSentTime > 60L) {
                    lastSentTime = now
                    val hsv = floatArrayOf(hue, saturation, valueBri)
                    val c = android.graphics.Color.HSVToColor(hsv)
                    onColorSelected(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
                }
            },
            onValueChangeFinished = {
                val hsv = floatArrayOf(hue, saturation, valueBri)
                val c = android.graphics.Color.HSVToColor(hsv)
                onColorSelected(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
            },
            valueRange = 0f..360f
        )

        // Preset Swatches M3
        Text(
            text = "Bảng màu tinh chọn",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = when {
                maxWidth >= 840.dp -> 6
                maxWidth >= 600.dp -> 4
                else -> 3
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LampConstants.PRESET_COLORS.chunked(columns).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowPresets.forEach { preset ->
                            ElevatedCard(
                                onClick = { onColorSelected(preset.r, preset.g, preset.b) },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = preset.color)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    contentAlignment = Alignment.BottomStart
                                ) {
                                    val isLight = (0.299 * preset.r + 0.587 * preset.g + 0.114 * preset.b) > 130
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isLight) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                        if (rowPresets.size < columns) {
                            repeat(columns - rowPresets.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// BẢNG FONT 5x6 VÀ COMPONENT PREVIEW CHỮ 360° THỜI GIAN THỰC
// ============================================================================

private val FONT_5X6_KOTLIN = mapOf(
    ' ' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00),
    '!' to intArrayOf(0x00, 0x00, 0x2F, 0x00, 0x00),
    '\"' to intArrayOf(0x03, 0x00, 0x03, 0x00, 0x00),
    '#' to intArrayOf(0x14, 0x3F, 0x14, 0x3F, 0x14),
    '$' to intArrayOf(0x12, 0x2A, 0x3F, 0x2A, 0x24),
    '%' to intArrayOf(0x23, 0x13, 0x08, 0x24, 0x31),
    '&' to intArrayOf(0x16, 0x29, 0x2D, 0x12, 0x20),
    '\'' to intArrayOf(0x00, 0x03, 0x00, 0x00, 0x00),
    '(' to intArrayOf(0x00, 0x1C, 0x22, 0x00, 0x00),
    ')' to intArrayOf(0x00, 0x22, 0x1C, 0x00, 0x00),
    '*' to intArrayOf(0x06, 0x0F, 0x1E, 0x0F, 0x06), // Heart!
    '+' to intArrayOf(0x00, 0x08, 0x3E, 0x08, 0x00),
    ',' to intArrayOf(0x00, 0x30, 0x20, 0x00, 0x00),
    '-' to intArrayOf(0x00, 0x08, 0x08, 0x08, 0x00),
    '.' to intArrayOf(0x00, 0x20, 0x20, 0x00, 0x00),
    '/' to intArrayOf(0x20, 0x10, 0x08, 0x04, 0x02),
    '0' to intArrayOf(0x1E, 0x21, 0x21, 0x21, 0x1E),
    '1' to intArrayOf(0x00, 0x22, 0x3F, 0x20, 0x00),
    '2' to intArrayOf(0x22, 0x31, 0x29, 0x25, 0x22),
    '3' to intArrayOf(0x12, 0x21, 0x25, 0x25, 0x1A),
    '4' to intArrayOf(0x0C, 0x0A, 0x09, 0x3F, 0x08),
    '5' to intArrayOf(0x17, 0x25, 0x25, 0x25, 0x19),
    '6' to intArrayOf(0x1E, 0x25, 0x25, 0x25, 0x18),
    '7' to intArrayOf(0x01, 0x31, 0x09, 0x05, 0x03),
    '8' to intArrayOf(0x1A, 0x25, 0x25, 0x25, 0x1A),
    '9' to intArrayOf(0x06, 0x29, 0x29, 0x29, 0x1E),
    ':' to intArrayOf(0x00, 0x14, 0x14, 0x00, 0x00),
    ';' to intArrayOf(0x00, 0x34, 0x24, 0x00, 0x00),
    '<' to intArrayOf(0x08, 0x14, 0x22, 0x00, 0x00),
    '=' to intArrayOf(0x14, 0x14, 0x14, 0x14, 0x14),
    '>' to intArrayOf(0x00, 0x22, 0x14, 0x08, 0x00),
    '?' to intArrayOf(0x02, 0x01, 0x29, 0x05, 0x02),
    '@' to intArrayOf(0x1E, 0x21, 0x2D, 0x2D, 0x1E),
    'A' to intArrayOf(0x3E, 0x09, 0x09, 0x09, 0x3E),
    'B' to intArrayOf(0x3F, 0x25, 0x25, 0x25, 0x1A),
    'C' to intArrayOf(0x1E, 0x21, 0x21, 0x21, 0x12),
    'D' to intArrayOf(0x3F, 0x21, 0x21, 0x12, 0x0C),
    'E' to intArrayOf(0x3F, 0x25, 0x25, 0x25, 0x21),
    'F' to intArrayOf(0x3F, 0x05, 0x05, 0x05, 0x01),
    'G' to intArrayOf(0x1E, 0x21, 0x25, 0x25, 0x3A),
    'H' to intArrayOf(0x3F, 0x04, 0x04, 0x04, 0x3F),
    'I' to intArrayOf(0x00, 0x21, 0x3F, 0x21, 0x00),
    'J' to intArrayOf(0x10, 0x20, 0x21, 0x1F, 0x01),
    'K' to intArrayOf(0x3F, 0x04, 0x0A, 0x11, 0x20),
    'L' to intArrayOf(0x3F, 0x20, 0x20, 0x20, 0x20),
    'M' to intArrayOf(0x3F, 0x02, 0x0C, 0x02, 0x3F),
    'N' to intArrayOf(0x3F, 0x02, 0x04, 0x08, 0x3F),
    'O' to intArrayOf(0x1E, 0x21, 0x21, 0x21, 0x1E),
    'P' to intArrayOf(0x3F, 0x09, 0x09, 0x09, 0x06),
    'Q' to intArrayOf(0x1E, 0x21, 0x29, 0x11, 0x2E),
    'R' to intArrayOf(0x3F, 0x09, 0x19, 0x29, 0x06),
    'S' to intArrayOf(0x12, 0x25, 0x25, 0x25, 0x18),
    'T' to intArrayOf(0x01, 0x01, 0x3F, 0x01, 0x01),
    'U' to intArrayOf(0x1F, 0x20, 0x20, 0x20, 0x1F),
    'V' to intArrayOf(0x0F, 0x10, 0x20, 0x10, 0x0F),
    'W' to intArrayOf(0x3F, 0x10, 0x0C, 0x10, 0x3F),
    'X' to intArrayOf(0x33, 0x0C, 0x0C, 0x33, 0x00),
    'Y' to intArrayOf(0x07, 0x08, 0x30, 0x08, 0x07),
    'Z' to intArrayOf(0x31, 0x29, 0x25, 0x23, 0x21),
    '[' to intArrayOf(0x00, 0x3F, 0x21, 0x00, 0x00),
    '\\' to intArrayOf(0x02, 0x04, 0x08, 0x10, 0x20),
    ']' to intArrayOf(0x00, 0x21, 0x3F, 0x00, 0x00),
    '^' to intArrayOf(0x04, 0x02, 0x01, 0x02, 0x04),
    '_' to intArrayOf(0x20, 0x20, 0x20, 0x20, 0x20),
    '~' to intArrayOf(0x02, 0x01, 0x02, 0x01, 0x00)
)

@Composable
fun CylinderMarqueePreview32x8(
    text: String,
    activeColor: Color,
    speedMs: Int,
    modifier: Modifier = Modifier
) {
    val cleanText = remember(text) {
        val s = sanitizeVietnameseForLed(text).trim()
        if (s.isEmpty()) "LOVE * YOU" else s
    }
    val charWidth = 6
    val gapCols = 8
    val textPixelWidth = cleanText.length * charWidth
    val loopWidth = maxOf(32, textPixelWidth + gapCols)

    var step by remember { mutableIntStateOf(0) }

    LaunchedEffect(cleanText, speedMs) {
        step = 0
        while (isActive) {
            delay(speedMs.toLong().coerceAtLeast(30L))
            step = (step + 1) % loopWidth
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F0F14))
            .border(1.dp, activeColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(activeColor)
                )
                Text(
                    text = "Mô phỏng LED 360° (4 Mặt x 8x8)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            Text(
                text = "$speedMs ms/cột",
                style = MaterialTheme.typography.labelSmall,
                color = activeColor
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val cols = 32
            val rows = 8
            val colSpacing = size.width / cols
            val rowSpacing = size.height / rows
            val dotRadius = minOf(colSpacing, rowSpacing) * 0.36f

            for (c in 0 until cols) {
                val ribbonX = (c + step) % loopWidth
                val isCharCol = ribbonX < textPixelWidth
                val charIdx = if (isCharCol) ribbonX / charWidth else 0
                val colInChar = if (isCharCol) ribbonX % charWidth else 0
                val charColBits = if (isCharCol && colInChar < 5) {
                    val ch = cleanText.getOrNull(charIdx) ?: ' '
                    FONT_5X6_KOTLIN[ch]?.getOrNull(colInChar) ?: 0
                } else 0

                val cx = c * colSpacing + colSpacing / 2f

                for (r in 0 until rows) {
                    val cy = r * rowSpacing + rowSpacing / 2f
                    val isLit = if (r in 1..6) {
                        ((charColBits shr (r - 1)) and 1) == 1
                    } else false

                    if (isLit) {
                        drawCircle(
                            color = activeColor.copy(alpha = 0.30f),
                            radius = dotRadius * 1.9f,
                            center = Offset(cx, cy)
                        )
                        drawCircle(
                            color = activeColor,
                            radius = dotRadius,
                            center = Offset(cx, cy)
                        )
                    } else {
                        drawCircle(
                            color = Color(0xFF22222E),
                            radius = dotRadius * 0.55f,
                            center = Offset(cx, cy)
                        )
                    }
                }
            }

            for (p in 1..3) {
                val lineX = p * 8 * colSpacing
                drawLine(
                    color = Color.White.copy(alpha = 0.15f),
                    start = Offset(lineX, 0f),
                    end = Offset(lineX, size.height),
                    strokeWidth = 1f
                )
            }
        }
    }
}

@Composable
fun MarqueeSection(
    initialText: String,
    onSendText: (String, Int?, Int?, Int?, Int) -> Unit
) {
    var textValue by remember(initialText) { mutableStateOf(initialText) }
    var selectedTextColor by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) } // null = mặc định Cyan (0, 220, 255)
    var selectedSpeed by remember { mutableIntStateOf(120) } // 160: Chậm, 120: Vừa, 80: Nhanh

    val activeColor = remember(selectedTextColor) {
        val rgb = selectedTextColor ?: Triple(0, 220, 255)
        Color(rgb.first, rgb.second, rgb.third)
    }

    val previewSanitized = remember(textValue) {
        sanitizeVietnameseForLed(textValue).trim()
    }

    val textColors = listOf(
        Pair("Cyan Biển", Triple(0, 220, 255)),
        Pair("Hồng Pastel", Triple(255, 105, 180)),
        Pair("Đỏ Tình Yêu", Triple(255, 40, 60)),
        Pair("Tím Oải Hương", Triple(190, 80, 255)),
        Pair("Vàng Nắng", Triple(255, 190, 30)),
        Pair("Xanh Lục", Triple(30, 240, 100)),
        Pair("Trắng Sáng", Triple(255, 255, 255))
    )

    val speedOptions = listOf(
        Pair("Chậm (160ms)", 160),
        Pair("Vừa (120ms)", 120),
        Pair("Nhanh (80ms)", 80)
    )

    val romanticPresets = listOf(
        Pair("ANH YÊU EM ❤️", "Tình yêu"),
        Pair("CHÚC NGỦ NGON 🌙", "Ngủ ngon"),
        Pair("HAPPY BIRTHDAY 🎂", "Sinh nhật"),
        Pair("EM LÀ DUY NHẤT 💕", "Duy nhất"),
        Pair("I LOVE YOU", "Love"),
        Pair("MÃI BÊN NHAU 💖", "Hẹn ước"),
        Pair("HÃY CƯỜI LÊN NHÉ 😊", "Động viên"),
        Pair("WELCOME HOME 🏡", "Về nhà"),
        Pair("COFFEE TIME ☕", "Cà phê")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // KHUNG XEM TRƯỚC MA TRẬN 32x8 THỜI GIAN THỰC
        CylinderMarqueePreview32x8(
            text = textValue,
            activeColor = activeColor,
            speedMs = selectedSpeed
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Subtitles,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Chữ ma trận xoay 360°",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Chữ cuộn ruy-băng khép kín liên tục quanh 4 mặt đèn",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it.take(64) },
                    label = { Text("Nội dung tin nhắn") },
                    placeholder = { Text("Nhập lời nhắn yêu thương...") },
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (previewSanitized.isNotEmpty()) {
                                Text(
                                    text = "Chuẩn LED: $previewSanitized",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            Text(
                                text = "${textValue.length}/64",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )

                // LỰA CHỌN TỐC ĐỘ CUỘN
                Text(
                    text = "Tốc độ cuộn chữ:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    speedOptions.forEach { (label, spd) ->
                        val isSelected = selectedSpeed == spd
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedSpeed = spd },
                            label = { Text(label) },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                // TÙY BIẾN MÀU SẮC CHỮ
                Text(
                    text = "Màu sắc chữ:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    textColors.forEach { (name, rgb) ->
                        val isSelected = selectedTextColor == rgb
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTextColor = if (isSelected) null else rgb },
                            label = { Text(name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(rgb.first, rgb.second, rgb.third))
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        val rgb = selectedTextColor
                        onSendText(textValue.trim(), rgb?.first, rgb?.second, rgb?.third, selectedSpeed)
                    },
                    enabled = textValue.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Bắn chữ lên đèn (360°)", fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Tin nhắn mẫu lãng mạn & ý nghĩa",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            romanticPresets.forEach { (msg, label) ->
                SuggestionChip(
                    onClick = {
                        textValue = msg
                        val rgb = selectedTextColor
                        onSendText(msg, rgb?.first, rgb?.second, rgb?.third, selectedSpeed)
                    },
                    label = { Text(label) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}

@Composable
fun PanoramaPreviewCanvas32x8(
    slots: List<SlotBitmapState>,
    updateTrigger: Int,
    modifier: Modifier = Modifier
) {
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = "Toàn cảnh trụ 360 độ 32x8 điểm ảnh"
            }
    ) {
        val spacing = 1.dp.toPx()
        val dividerWidth = 2.dp.toPx()
        val totalSpacingW = spacing * 28 + dividerWidth * 3
        val cellW = (size.width - totalSpacingW) / 32f
        val cellH = (size.height - spacing * 7) / 8f

        for (y in 0 until 8) {
            var currentLeft = 0f
            for (sIdx in 0 until 4) {
                for (x in 0 until 8) {
                    val px = slots[sIdx].getPixel(x, y)
                    val fill = if (px == android.graphics.Color.BLACK) inactiveColor else Color(px)
                    val top = y * (cellH + spacing)

                    drawRect(
                        color = fill,
                        topLeft = Offset(currentLeft, top),
                        size = Size(cellW, cellH)
                    )
                    currentLeft += cellW + spacing
                }
                if (sIdx < 3) {
                    currentLeft += dividerWidth - spacing
                    drawRect(
                        color = dividerColor,
                        topLeft = Offset(currentLeft - dividerWidth, 0f),
                        size = Size(dividerWidth, size.height)
                    )
                }
            }
        }
    }
}

@Composable
fun PixelGridCanvas8x8(
    slot: SlotBitmapState,
    updateTrigger: Int,
    selectedColor: Int,
    isEraserMode: Boolean,
    onPixelChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = "Khung vẽ 8x8 điểm ảnh. Chạm hoặc vuốt để tô màu."
            }
            .pointerInput(selectedColor, isEraserMode, slot) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val cellW = size.width / 8f
                        val cellH = size.height / 8f
                        val x = (offset.x / cellW).toInt().coerceIn(0, 7)
                        val y = (offset.y / cellH).toInt().coerceIn(0, 7)
                        val newC = if (isEraserMode) android.graphics.Color.BLACK else selectedColor
                        slot.setPixel(x, y, newC)
                        onPixelChange()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val cellW = size.width / 8f
                        val cellH = size.height / 8f
                        val x = (change.position.x / cellW).toInt().coerceIn(0, 7)
                        val y = (change.position.y / cellH).toInt().coerceIn(0, 7)
                        val newC = if (isEraserMode) android.graphics.Color.BLACK else selectedColor
                        slot.setPixel(x, y, newC)
                        onPixelChange()
                    }
                )
            }
    ) {
        val spacing = 4.dp.toPx()
        val cellW = (size.width - spacing * 7) / 8f
        val cellH = (size.height - spacing * 7) / 8f
        val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val px = slot.getPixel(x, y)
                val fill = if (px == android.graphics.Color.BLACK) inactiveColor else Color(px)
                val left = x * (cellW + spacing)
                val top = y * (cellH + spacing)

                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, top),
                    size = Size(cellW, cellH),
                    cornerRadius = corner
                )
            }
        }
    }
}

@Composable
fun PixelGridCanvas32x8(
    fullCanvas: PixelCanvasState,
    updateTrigger: Int,
    selectedColor: Int,
    isEraserMode: Boolean,
    onPixelChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = "Ma trận toàn cảnh 32x8 điểm ảnh. Chạm hoặc vuốt để vẽ."
            }
            .pointerInput(selectedColor, isEraserMode, fullCanvas) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val cellW = size.width / 32f
                        val cellH = size.height / 8f
                        val x = (offset.x / cellW).toInt().coerceIn(0, 31)
                        val y = (offset.y / cellH).toInt().coerceIn(0, 7)
                        val newC = if (isEraserMode) android.graphics.Color.BLACK else selectedColor
                        fullCanvas.setPixel(x, y, newC)
                        onPixelChange()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val cellW = size.width / 32f
                        val cellH = size.height / 8f
                        val x = (change.position.x / cellW).toInt().coerceIn(0, 31)
                        val y = (change.position.y / cellH).toInt().coerceIn(0, 7)
                        val newC = if (isEraserMode) android.graphics.Color.BLACK else selectedColor
                        fullCanvas.setPixel(x, y, newC)
                        onPixelChange()
                    }
                )
            }
    ) {
        val spacing = 2.dp.toPx()
        val totalSpacingW = spacing * 31
        val cellW = (size.width - totalSpacingW) / 32f
        val cellH = (size.height - spacing * 7) / 8f
        val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())

        for (y in 0 until 8) {
            for (x in 0 until 32) {
                val px = fullCanvas.getPixel(x, y)
                val fill = if (px == android.graphics.Color.BLACK) inactiveColor else Color(px)
                val left = x * (cellW + spacing)
                val top = y * (cellH + spacing)

                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, top),
                    size = Size(cellW, cellH),
                    cornerRadius = corner
                )
            }
        }
    }
}

@Composable
fun PixelArtCanvasSection(
    onSendSlot: (slot: Int, r: Int, g: Int, b: Int, rows: ByteArray) -> Unit,
    onSendAllSlots: (slots: List<SlotBitmapState>) -> Unit,
    onSendFullCanvas: (PixelCanvasState) -> Unit,
    onActivateMode11: () -> Unit
) {
    val slots = remember {
        listOf(
            SlotBitmapState(1).apply {
                val p = LampConstants.SLOT_PRESETS[0]
                for (y in 0..7) {
                    val rowVal = p.rows[y]
                    for (x in 0..7) {
                        if (((rowVal shr (7 - x)) and 1) == 1) setPixel(x, y, android.graphics.Color.rgb(p.r, p.g, p.b))
                    }
                }
            },
            SlotBitmapState(2).apply {
                val p = LampConstants.SLOT_PRESETS[1]
                for (y in 0..7) {
                    val rowVal = p.rows[y]
                    for (x in 0..7) {
                        if (((rowVal shr (7 - x)) and 1) == 1) setPixel(x, y, android.graphics.Color.rgb(p.r, p.g, p.b))
                    }
                }
            },
            SlotBitmapState(3).apply {
                val p = LampConstants.SLOT_PRESETS[2]
                for (y in 0..7) {
                    val rowVal = p.rows[y]
                    for (x in 0..7) {
                        if (((rowVal shr (7 - x)) and 1) == 1) setPixel(x, y, android.graphics.Color.rgb(p.r, p.g, p.b))
                    }
                }
            },
            SlotBitmapState(4).apply {
                val p = LampConstants.SLOT_PRESETS[3]
                for (y in 0..7) {
                    val rowVal = p.rows[y]
                    for (x in 0..7) {
                        if (((rowVal shr (7 - x)) and 1) == 1) setPixel(x, y, android.graphics.Color.rgb(p.r, p.g, p.b))
                    }
                }
            }
        )
    }

    val fullCanvas = remember {
        PixelCanvasState(32, 8).apply {
            LampConstants.FULL_CANVAS_PRESETS[0].generator(this)
        }
    }

    var canvasModeTab by remember { mutableIntStateOf(0) } // 0: 4 Ô Độc Lập, 1: Canvas 32x8
    var selectedSlotIndex by remember { mutableIntStateOf(0) }
    var selectedColor by remember { mutableIntStateOf(android.graphics.Color.CYAN) }
    var isEraserMode by remember { mutableStateOf(false) }
    var updateCounter by remember { mutableIntStateOf(0) }

    val activeSlot = slots[selectedSlotIndex]

    val paletteColors = listOf(
        android.graphics.Color.CYAN,
        android.graphics.Color.RED,
        android.graphics.Color.GREEN,
        android.graphics.Color.YELLOW,
        android.graphics.Color.MAGENTA,
        android.graphics.Color.WHITE,
        android.graphics.Color.BLUE,
        android.graphics.Color.rgb(255, 140, 0)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode switch — M3 segmented control (role semantics built-in)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            SegmentedButton(
                selected = canvasModeTab == 0,
                onClick = { canvasModeTab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text("4 Mặt (8x8)") }
            )
            SegmentedButton(
                selected = canvasModeTab == 1,
                onClick = { canvasModeTab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text("Canvas 32x8") }
            )
        }

        if (canvasModeTab == 0) {
            // ==========================================
            // CHẾ ĐỘ 1: VẼ 4 Ô ĐỘC LẬP
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                slots.forEachIndexed { idx, s ->
                    val isSelected = selectedSlotIndex == idx
                    val dominant = s.getDominantColor()
                    val chipColor = Color(dominant.first, dominant.second, dominant.third)

                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedSlotIndex = idx },
                        label = { Text("Ô ${idx + 1}") },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(chipColor)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }

            // Toàn cảnh xem trước 32x8
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Toàn cảnh trụ tròn 360°:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    PanoramaPreviewCanvas32x8(
                        slots = slots,
                        updateTrigger = updateCounter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            // Khung vẽ 8x8 cho Ô đang chọn
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Vẽ Ô ${selectedSlotIndex + 1} (Mặt ${selectedSlotIndex + 1}):",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { activeSlot.clear(); updateCounter++ }) {
                        Text("Xóa ô", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { activeSlot.fill(selectedColor); updateCounter++ }) {
                        Text("Tô màu")
                    }
                }
            }

            // Grid 8x8
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    PixelGridCanvas8x8(
                        slot = activeSlot,
                        updateTrigger = updateCounter,
                        selectedColor = selectedColor,
                        isEraserMode = isEraserMode,
                        onPixelChange = { updateCounter++ },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Palette màu & Tẩy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paletteColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (selectedColor == c && !isEraserMode) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape
                                )
                                .clickable {
                                    selectedColor = c
                                    isEraserMode = false
                                }
                        )
                    }
                }

                IconButton(
                    onClick = { isEraserMode = !isEraserMode },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isEraserMode) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Tẩy",
                        tint = if (isEraserMode) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Presets nhanh
            Text(
                text = "Mẫu nhanh:",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LampConstants.SLOT_PRESETS.forEach { preset ->
                    SuggestionChip(
                        onClick = {
                            activeSlot.clear()
                            for (y in 0..7) {
                                val rowVal = preset.rows[y]
                                for (x in 0..7) {
                                    if (((rowVal shr (7 - x)) and 1) == 1) {
                                        activeSlot.setPixel(x, y, android.graphics.Color.rgb(preset.r, preset.g, preset.b))
                                    }
                                }
                            }
                            updateCounter++
                        },
                        label = { Text(preset.name) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Nút gửi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val (r, g, b) = activeSlot.getDominantColor()
                        val rows = activeSlot.to1BitBitmap()
                        onSendSlot(activeSlot.id, r, g, b, rows)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(text = "Gửi Ô ${activeSlot.id}")
                }

                Button(
                    onClick = { onSendAllSlots(slots) },
                    modifier = Modifier
                        .weight(1.3f)
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Gửi 4 Ô (Mode 11)")
                }
            }

        } else {
            // ==========================================
            // CHẾ ĐỘ 2: VẼ TOÀN BỘ 32x8
            // ==========================================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    PixelGridCanvas32x8(
                        fullCanvas = fullCanvas,
                        updateTrigger = updateCounter,
                        selectedColor = selectedColor,
                        isEraserMode = isEraserMode,
                        onPixelChange = { updateCounter++ },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                    )
                }
            }

            // Palette màu cho 32x8
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paletteColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (selectedColor == c && !isEraserMode) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape
                                )
                                .clickable {
                                    selectedColor = c
                                    isEraserMode = false
                                }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = { isEraserMode = !isEraserMode },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (isEraserMode) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Tẩy",
                            tint = if (isEraserMode) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { fullCanvas.clear(); updateCounter++ }) {
                        Text("Xóa hết", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Mẫu 32x8
            Text(
                text = "Thư viện mẫu 32x8:",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LampConstants.FULL_CANVAS_PRESETS.forEach { preset ->
                    SuggestionChip(
                        onClick = {
                            preset.generator(fullCanvas)
                            updateCounter++
                        },
                        label = { Text(preset.name) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Button(
                onClick = { onSendFullCanvas(fullCanvas) },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Bắn toàn bộ Canvas 32x8 lên đèn", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3TimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit
) {
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        selectorColor = MaterialTheme.colorScheme.primary,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        clockDialSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
                        clockDialUnselectedContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                Text("Chọn")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}

// ============================================================================
// 3. MÀN HÌNH CHÍNH 3: KỊCH BẢN (ROUTINES)
// ============================================================================

@Composable
fun RoutinesScreen(
    routineConfig: SmartRoutineConfig,
    weatherData: WeatherData,
    onUpdateRoutine: (SmartRoutineConfig) -> Unit,
    onCitySelected: (String) -> Unit,
    onSyncWeatherNow: () -> Unit,
    onPreviewModeSelect: (Int) -> Unit,
    onTriggerSunriseDemo: () -> Unit,
    onActivateBedtimeRoutine: () -> Unit,
    onTriggerWeatherAnimation: ((temp: Int, weatherId: Int, modeId: Int) -> Unit)? = null
) {
    var showSunrisePicker by remember { mutableStateOf(false) }
    var showBedtimePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // BÁO THỨC BÌNH MINH
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = AmberWarm.copy(alpha = 0.2f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.WbSunny,
                                    contentDescription = null,
                                    tint = AmberWarm,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Báo thức bình minh",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Ánh sáng tăng dần tự nhiên",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = routineConfig.sunriseEnabled,
                        onCheckedChange = { onUpdateRoutine(routineConfig.copy(sunriseEnabled = it)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Giờ thức dậy:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Bấm để mở Compose Material 3 TimePickerDialog
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable { showSunrisePicker = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.AccessTime,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = String.format("%02d:%02d", routineConfig.sunriseHour, routineConfig.sunriseMinute),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Text(
                    text = "Thời gian sáng dần: ${routineConfig.sunriseDurationMin} phút trước giờ thức",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = routineConfig.sunriseDurationMin.toFloat(),
                    onValueChange = { onUpdateRoutine(routineConfig.copy(sunriseDurationMin = it.toInt())) },
                    valueRange = 5f..30f,
                    steps = 4
                )

                FilledTonalButton(
                    onClick = onTriggerSunriseDemo,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Filled.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Chạy thử bình minh (Demo 10s)")
                }
            }
        }

        // ĐÈN NGỦ TỰ ĐỘNG
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Bedtime,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Đèn ngủ tự động",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Nhịp thở êm dịu + Tự tắt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = routineConfig.bedtimeEnabled,
                        onCheckedChange = { onUpdateRoutine(routineConfig.copy(bedtimeEnabled = it)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Giờ đi ngủ:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Bấm để mở Compose Material 3 TimePickerDialog chọn giờ ngủ
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable { showBedtimePicker = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.AccessTime,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = String.format("%02d:%02d", routineConfig.bedtimeHour, routineConfig.bedtimeMinute),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                Text(
                    text = "Tự tắt sau:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        FilterChip(
                            selected = routineConfig.bedtimeDurationMin == mins,
                            onClick = { onUpdateRoutine(routineConfig.copy(bedtimeDurationMin = mins)) },
                            label = { Text("${mins}p") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onActivateBedtimeRoutine,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Bật đèn ngủ ngay (Tự tắt)")
                }
            }
        }

        // ĐỒNG BỘ THỜI TIẾT TỰ ĐỘNG
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                var expanded by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.WbSunny,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Thời tiết thông minh",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Đồng bộ nhiệt độ và dự báo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Nút chọn thành phố
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(weatherData.cityName, style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            LampConstants.CITIES.forEach { city ->
                                DropdownMenuItem(
                                    text = { Text(city.first) },
                                    onClick = {
                                        onCitySelected(city.first)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Công tắc tự động đổi hiệu ứng theo thời tiết
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tự đổi hoạt hình theo thời tiết",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Đèn tự chuyển hiệu ứng tương ứng trời mưa, nắng, sấm chớp",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = routineConfig.autoWeatherSync,
                        onCheckedChange = { onUpdateRoutine(routineConfig.copy(autoWeatherSync = it)) }
                    )
                }

                // Overview card với trạng thái tự động cập nhật
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${weatherData.weatherIcon} ${weatherData.weatherName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Nhiệt độ hiện tại: ${weatherData.temp}°C",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${weatherData.temp}°",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Trạng thái tự động cập nhật
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Autorenew,
                                contentDescription = null,
                                tint = GreenJade,
                                modifier = Modifier.size(16.dp)
                            )
                            val timeStr = remember(weatherData.lastUpdatedTime) {
                                val cal = java.util.Calendar.getInstance().apply { timeInMillis = weatherData.lastUpdatedTime }
                                String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                            }
                            Text(
                                text = "Tự động đồng bộ mỗi 15 phút (vừa cập nhật lúc $timeStr)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onSyncWeatherNow,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Làm mới & đồng bộ ngay xuống đèn")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Trải nghiệm tức thì Animation Thời tiết 360°:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    val weatherModes = listOf(
                        Triple("☀️ Nắng đẹp (Mode 14)", 1, 14),
                        Triple("🌧️ Mưa rơi (Mode 15)", 3, 15),
                        Triple("☁️ Mây trôi (Mode 16)", 2, 16),
                        Triple("🌩️ Giông bão (Mode 17)", 4, 17)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        weatherModes.take(2).forEach { (label, wId, mId) ->
                            OutlinedButton(
                                onClick = {
                                    onTriggerWeatherAnimation?.invoke(weatherData.temp, wId, mId)
                                        ?: onPreviewModeSelect(mId)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        weatherModes.drop(2).forEach { (label, wId, mId) ->
                            OutlinedButton(
                                onClick = {
                                    onTriggerWeatherAnimation?.invoke(weatherData.temp, wId, mId)
                                        ?: onPreviewModeSelect(mId)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSunrisePicker) {
            M3TimePickerDialog(
                title = "Chọn giờ báo thức bình minh",
                initialHour = routineConfig.sunriseHour,
                initialMinute = routineConfig.sunriseMinute,
                onDismiss = { showSunrisePicker = false },
                onConfirm = { h, m ->
                    onUpdateRoutine(routineConfig.copy(sunriseHour = h, sunriseMinute = m))
                    showSunrisePicker = false
                }
            )
        }

        if (showBedtimePicker) {
            M3TimePickerDialog(
                title = "Chọn giờ đi ngủ",
                initialHour = routineConfig.bedtimeHour,
                initialMinute = routineConfig.bedtimeMinute,
                onDismiss = { showBedtimePicker = false },
                onConfirm = { h, m ->
                    onUpdateRoutine(routineConfig.copy(bedtimeHour = h, bedtimeMinute = m))
                    showBedtimePicker = false
                }
            )
        }
    }
}

// ============================================================================
// 4. MÀN HÌNH CHÍNH 4: THIẾT BỊ (DEVICE)
// ============================================================================

@Composable
fun DeviceScreen(
    telemetry: TelemetryState,
    logs: List<String>,
    bleManager: BleManager,
    notifConfigs: List<AppNotificationConfig>,
    onSaveAppConfig: (AppNotificationConfig) -> Unit,
    onAddAppConfig: (AppNotificationConfig) -> Unit,
    onDeleteAppConfig: (String) -> Unit,
    onConfigToggle: (String, Boolean) -> Unit,
    onTestFlash: (Int, Int, Int, Int) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onTriggerWarmLight: () -> Unit,
    onTriggerPomodoro: () -> Unit = onTriggerWarmLight,
    onSyncNow: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onPickFirmwareFile: () -> Unit,
    isDynamicColor: Boolean = false,
    onToggleDynamicColor: (Boolean) -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    onShowMessageConnect: (String) -> Unit = {}
) {
    var editingAppConfig by remember { mutableStateOf<AppNotificationConfig?>(null) }
    var showAddAppDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. THẺ QUẢN LÝ KẾT NỐI BLUETOOTH & TÌM KIẾM ĐÈN
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            val connState by bleManager.connectionState.collectAsState()
            val isConnected by bleManager.isConnected.collectAsState()
            val isScanning by bleManager.isScanning.collectAsState()
            val scannedDevices by bleManager.scannedDevices.collectAsState()
            val connectedName by bleManager.connectedDeviceName.collectAsState()
            val connectedAddr by bleManager.connectedDeviceAddress.collectAsState()
            val signalRssi by bleManager.signalRssi.collectAsState()
            val isAutoConnect by bleManager.isAutoConnectEnabled.collectAsState()
            var showTroubleshooting by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header: Icon + Trạng thái kết nối
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isConnected) GreenJade.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Filled.BluetoothConnected else (if (isScanning) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Filled.Bluetooth),
                                    contentDescription = null,
                                    tint = if (isConnected) GreenJade else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Kết nối Bluetooth BLE",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isConnected) GreenJade
                                            else if (isScanning || connState == BleConnectionState.CONNECTING || connState == BleConnectionState.NEGOTIATING_MTU || connState == BleConnectionState.DISCOVERING_SERVICES) AmberWarm
                                            else MaterialTheme.colorScheme.error
                                        )
                                )
                                Text(
                                    text = connState.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isConnected) GreenJade else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Toggle Auto-connect
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Tự kết nối",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = isAutoConnect,
                            onCheckedChange = { bleManager.setAutoConnectEnabled(it) }
                        )
                    }
                }

                // Chi tiết thiết bị khi đã kết nối
                if (isConnected) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = connectedName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "MAC: $connectedAddr",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (signalRssi != 0) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Text(
                                        text = "${signalRssi} dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // Nút hành động chính: Quét thiết bị / Ngắt kết nối
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isConnected) {
                        OutlinedButton(
                            onClick = {
                                bleManager.disconnect()
                                onShowMessage("Đã ngắt kết nối với đèn")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ngắt kết nối")
                        }

                        FilledTonalButton(
                            onClick = {
                                bleManager.readRssi()
                                onShowMessage("Đang đo sóng RSSI...")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Đo sóng RSSI")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (isScanning) {
                                    bleManager.stopScan()
                                    onShowMessage("Đã dừng quét")
                                } else {
                                    bleManager.clearScannedDevices()
                                    bleManager.startScan()
                                    onShowMessage("Đang quét tìm đèn LED Matrix 360...")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Dừng quét")
                            } else {
                                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Quét tìm đèn")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                bleManager.autoConnectLastDevice()
                                onShowMessage("Đang thử kết nối lại đèn đã lưu...")
                            },
                            enabled = !isScanning,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Kết nối lại")
                        }
                    }
                }

                // Danh sách thiết bị quét được (khi không kết nối và có thiết bị)
                if (!isConnected && scannedDevices.isNotEmpty()) {
                    Text(
                        text = "Thiết bị tìm thấy (${scannedDevices.size}):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        scannedDevices.forEach { dev ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        bleManager.stopScan()
                                        bleManager.connect(dev.device)
                                        onShowMessage("Đang kết nối tới ${dev.name}...")
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lightbulb,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column {
                                            Text(
                                                text = dev.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = dev.address,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "${dev.rssi} dBm",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        FilledTonalButton(
                                            onClick = {
                                                bleManager.stopScan()
                                                bleManager.connect(dev.device)
                                                onShowMessage("Đang kết nối tới ${dev.name}...")
                                            },
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Kết nối", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Mục trợ giúp khắc phục kết nối (Troubleshooting)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTroubleshooting = !showTroubleshooting }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Đèn không kết nối được? Xem hướng dẫn",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = if (showTroubleshooting) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (showTroubleshooting) {
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "1. Cấp nguồn: Kiểm tra đèn đã cắm sạc/nguồn 5V và LED báo hiệu đang sáng.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "2. Bluetooth & Vị trí: Đảm bảo Bluetooth và GPS (trên máy đời cũ) đã được BẬT trên điện thoại.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "3. Khoảng cách: Đưa điện thoại lại gần đèn trong phạm vi 1 - 3 mét.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "4. QUAN TRỌNG: Nếu đèn đang kết nối với máy tính qua trang Web Bluetooth (start_web.bat / index.html), ESP32 sẽ ẩn sóng. Hãy nhấn 'Ngắt kết nối' trên trình duyệt máy tính trước khi kết nối từ điện thoại!",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AmberWarm
                                )
                            }
                        }
                    }
                }
            }
        }

        // CẢM BIẾN MPU-6050 & ĐÈN VÀNG ẤM NẰM NGANG
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.ScreenRotation,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Cảm biến MPU-6050 & Đèn vàng ấm",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Pitch: ${telemetry.pitch.toInt()}° | Roll: ${telemetry.roll.toInt()}° (Nghiêng ngang > 60°: Tự bật Đèn vàng ấm 2200K)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            onTriggerWarmLight()
                            onShowMessage("Đã bật Đèn vàng ấm thân thiện mắt (2200K)")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.LightMode, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Đèn vàng ấm 2200K")
                    }

                    OutlinedButton(
                        onClick = {
                            onSyncNow()
                            onShowMessage("Đang đồng bộ Giờ & Thời tiết xuống đèn...")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Đồng bộ RTC")
                    }
                }
            }
        }

        // THÔNG BÁO HỆ THỐNG - ĐỒNG BỘ MÀU TỪNG ỨNG DỤNG
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Đồng bộ thông báo app",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Chạm vào app để tùy biến màu chớp",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showAddAppDialog = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Thêm app", modifier = Modifier.size(22.dp))
                        }

                        OutlinedButton(
                            onClick = onOpenNotificationSettings,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Quyền", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Danh sách ứng dụng
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    notifConfigs.forEach { app ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { editingAppConfig = app }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // Chấm màu đại diện (chạm để mở dialog màu)
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color(app.r, app.g, app.b))
                                            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    )
                                    Column {
                                        Text(
                                            text = app.appName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "RGB(${app.r}, ${app.g}, ${app.b}) • ${app.flashCount} lần",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalIconButton(
                                        onClick = { onTestFlash(app.r, app.g, app.b, app.flashCount) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Filled.FlashOn, contentDescription = "Thử chớp", modifier = Modifier.size(20.dp))
                                    }

                                    Switch(
                                        checked = app.isEnabled,
                                        onCheckedChange = { onConfigToggle(app.packageName, it) },
                                        modifier = Modifier.padding(start = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // CÀI ĐẶT GIAO DIỆN & MATERIAL YOU
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Giao diện & Màu sắc",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Material You Dynamic Color",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isDynamicColor,
                        onCheckedChange = onToggleDynamicColor
                    )
                }
                Text(
                    text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Tự động đồng bộ bảng màu giao diện với hình nền điện thoại của bạn (Android 12+)."
                    } else {
                        "Dynamic Color yêu cầu Android 12 trở lên. Hiện đang dùng bảng màu Cyberpunk Dark cố định."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // CẬP NHẬT FIRMWARE OTA
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            val isConnected by bleManager.isConnected.collectAsState()
            val progress by bleManager.otaProgress.collectAsState()
            val statusMsg by bleManager.otaStatus.collectAsState()

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Nạp Firmware qua BLE (OTA)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Nâng cấp phần mềm không dây",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!isConnected) {
                                onShowMessageConnect("Chưa kết nối đèn — không vào được OTA")
                            } else {
                                bleManager.sendCommand("ota")
                                onShowMessage("Đã gửi lệnh vào chế độ OTA")
                            }
                        },
                        enabled = progress == null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Vào mode OTA")
                    }

                    OutlinedButton(
                        onClick = {
                            if (!isConnected) {
                                onShowMessageConnect("Chưa kết nối đèn — không hủy được OTA")
                            } else {
                                bleManager.sendCommand("ota abort")
                                onShowMessage("Đã gửi lệnh hủy OTA")
                            }
                        },
                        enabled = progress == null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Hủy OTA")
                    }
                }

                Button(
                    onClick = {
                        if (!isConnected) {
                            onShowMessageConnect("Chưa kết nối đèn — không chọn được firmware")
                        } else {
                            onPickFirmwareFile()
                        }
                    },
                    enabled = progress == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Chọn file firmware.bin để nạp")
                }

                if (progress != null || statusMsg.isNotEmpty()) {
                    Text(
                        text = statusMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    progress?.let { p ->
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }

        // NHẬT KÝ TELEMETRY & BLUETOOTH
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nhật ký hệ thống",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${logs.size} sự kiện",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (logs.isNotEmpty()) {
                            TextButton(
                                onClick = { bleManager.clearLogs() },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Xóa", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // DIALOG TÙY BIẾN MÀU SẮC & SỐ LẦN CHỚP CỦA APP
    editingAppConfig?.let { app ->
        AppNotificationColorDialog(
            app = app,
            onDismiss = { editingAppConfig = null },
            onSave = { updated ->
                onSaveAppConfig(updated)
                editingAppConfig = null
            },
            onDelete = {
                onDeleteAppConfig(app.packageName)
                editingAppConfig = null
            },
            onTestFlash = onTestFlash
        )
    }

    // DIALOG THÊM APP THEO DÕI MỚI
    if (showAddAppDialog) {
        AddCustomAppDialog(
            existingPackages = notifConfigs.map { it.packageName }.toSet(),
            onDismiss = { showAddAppDialog = false },
            onAdd = { newApp ->
                onAddAppConfig(newApp)
                showAddAppDialog = false
            }
        )
    }
}

// ============================================================================
// 5. CÁC MODAL DIALOGS PHỤ TRỢ (COLOR PICKER & ADD APP)
// ============================================================================

@Composable
fun AppNotificationColorDialog(
    app: AppNotificationConfig,
    onDismiss: () -> Unit,
    onSave: (AppNotificationConfig) -> Unit,
    onDelete: () -> Unit,
    onTestFlash: (Int, Int, Int, Int) -> Unit
) {
    var curR by remember { mutableFloatStateOf(app.r.toFloat()) }
    var curG by remember { mutableFloatStateOf(app.g.toFloat()) }
    var curB by remember { mutableFloatStateOf(app.b.toFloat()) }
    var curCount by remember { mutableIntStateOf(app.flashCount) }

    val presetSwatches = listOf(
        Pair("Đỏ", Triple(255, 20, 20)),
        Pair("Cam", Triple(255, 140, 0)),
        Pair("Vàng", Triple(255, 215, 0)),
        Pair("Xanh lá", Triple(20, 240, 70)),
        Pair("Cyan", Triple(0, 220, 255)),
        Pair("Xanh dương", Triple(0, 140, 255)),
        Pair("Tím", Triple(180, 40, 240)),
        Pair("Hồng", Triple(255, 60, 180)),
        Pair("Trắng", Triple(255, 255, 255))
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(curR.toInt(), curG.toInt(), curB.toInt()))
                )
                Text(
                    text = "Màu đèn cho ${app.appName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Thẻ xem trước màu sắc lớn
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(curR.toInt(), curG.toInt(), curB.toInt()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val isLight = (0.299 * curR + 0.587 * curG + 0.114 * curB) > 130
                        Text(
                            text = "RGB(${curR.toInt()}, ${curG.toInt()}, ${curB.toInt()})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isLight) Color.Black else Color.White
                        )
                    }
                }

                // Preset Swatches
                Text(
                    text = "Màu mẫu nhanh:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetSwatches.forEach { (name, rgb) ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(rgb.first, rgb.second, rgb.third))
                                .border(
                                    if (curR.toInt() == rgb.first && curG.toInt() == rgb.second && curB.toInt() == rgb.third) 2.5.dp else 0.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape
                                )
                                .clickable {
                                    curR = rgb.first.toFloat()
                                    curG = rgb.second.toFloat()
                                    curB = rgb.third.toFloat()
                                }
                        )
                    }
                }

                // Thanh trượt R, G, B
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Đỏ (Red: ${curR.toInt()})", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = curR,
                        onValueChange = { curR = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(thumbColor = Color.Red, activeTrackColor = Color.Red)
                    )

                    Text(text = "Lục (Green: ${curG.toInt()})", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = curG,
                        onValueChange = { curG = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
                    )

                    Text(text = "Lam (Blue: ${curB.toInt()})", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = curB,
                        onValueChange = { curB = it },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(thumbColor = Color.Blue, activeTrackColor = Color.Blue)
                    )
                }

                // Chọn số lần chớp
                Text(
                    text = "Số lần chớp sáng:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 2, 3, 5).forEach { count ->
                        FilterChip(
                            selected = curCount == count,
                            onClick = { curCount = count },
                            label = { Text("$count lần") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                // Nút thử chớp ngay
                OutlinedButton(
                    onClick = { onTestFlash(curR.toInt(), curG.toInt(), curB.toInt(), curCount) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Thử chớp ngay lên đèn")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        app.copy(
                            r = curR.toInt(),
                            g = curG.toInt(),
                            b = curB.toInt(),
                            flashCount = curCount
                        )
                    )
                }
            ) {
                Text("Lưu màu")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Xóa app")
                }
                TextButton(onClick = onDismiss) {
                    Text("Hủy")
                }
            }
        }
    )
}

@Composable
fun AddCustomAppDialog(
    existingPackages: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (AppNotificationConfig) -> Unit
) {
    var appName by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var selectedRgb by remember { mutableStateOf(Triple(0, 220, 255)) }
    var flashCount by remember { mutableIntStateOf(3) }

    val presetApps = listOf(
        Triple("SMS / Tin nhắn", "com.google.android.apps.messaging", Triple(255, 140, 0)),
        Triple("Instagram", "com.instagram.android", Triple(255, 60, 180)),
        Triple("TikTok", "com.ss.android.ugc.trill", Triple(0, 240, 255)),
        Triple("Facebook", "com.facebook.katana", Triple(0, 140, 255)),
        Triple("Shopee", "com.shopee.vn", Triple(255, 80, 0)),
        Triple("Discord", "com.discord", Triple(114, 137, 218)),
        Triple("Slack", "com.Slack", Triple(74, 21, 75)),
        Triple("YouTube", "com.google.android.youtube", Triple(255, 20, 20))
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = "Thêm ứng dụng theo dõi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Gợi ý ứng dụng phổ biến:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetApps.filter { !existingPackages.contains(it.second) }.forEach { (name, pkg, rgb) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    appName = name
                                    packageName = pkg
                                    selectedRgb = rgb
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(rgb.first, rgb.second, rgb.third))
                                )
                                Text(text = name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("Tên ứng dụng") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package name (ví dụ com.app)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                enabled = appName.isNotBlank() && packageName.isNotBlank(),
                onClick = {
                    onAdd(
                        AppNotificationConfig(
                            packageName = packageName.trim(),
                            appName = appName.trim(),
                            iconEmoji = "💬",
                            r = selectedRgb.first,
                            g = selectedRgb.second,
                            b = selectedRgb.third,
                            isEnabled = true,
                            flashCount = flashCount
                        )
                    )
                }
            ) {
                Text("Thêm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}
