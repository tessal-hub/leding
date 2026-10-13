@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.esp32.ledmatrix360.ui

import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esp32.ledmatrix360.ble.BleManager
import com.esp32.ledmatrix360.model.*

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
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f),
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
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when (state.powerState) {
                        1 -> Icons.Filled.BatteryChargingFull
                        2 -> Icons.Filled.BatteryFull
                        else -> Icons.Filled.BatteryStd
                    },
                    contentDescription = null,
                    tint = when (state.powerState) {
                        1 -> AmberWarm
                        2 -> GreenJade
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = when (state.powerState) {
                            1 -> "Đang sạc"
                            2 -> "Pin đầy"
                            else -> "Dùng pin"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.statMv} mV",
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
            modifier = Modifier.weight(1f)
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
            modifier = Modifier.weight(1f)
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LampConstants.MODES.chunked(2).forEach { rowModes ->
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
                if (rowModes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

fun getModeVectorIcon(modeId: Int): ImageVector {
    return when (modeId) {
        0 -> Icons.Filled.Autorenew
        1 -> Icons.Filled.LocalFireDepartment
        2 -> Icons.Filled.CropFree
        3 -> Icons.Filled.Waves
        4 -> Icons.Filled.ScreenRotation
        5 -> Icons.Filled.WaterDrop
        6 -> Icons.Filled.NightsStay
        7 -> Icons.Filled.BlurOn
        8 -> Icons.Filled.LinearScale
        9 -> Icons.Filled.PowerSettingsNew
        10 -> Icons.Filled.ColorLens
        11 -> Icons.Filled.GridOn
        12 -> Icons.Filled.AccessTime
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
        modifier = modifier,
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
    onSendMarqueeText: (String, Int?, Int?, Int?) -> Unit,
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
                .height(130.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = currentColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LampConstants.PRESET_COLORS.chunked(3).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowPresets.forEach { preset ->
                        ElevatedCard(
                            onClick = { onColorSelected(preset.r, preset.g, preset.b) },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
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
                    if (rowPresets.size < 3) {
                        repeat(3 - rowPresets.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MarqueeSection(
    initialText: String,
    onSendText: (String, Int?, Int?, Int?) -> Unit
) {
    var textValue by remember(initialText) { mutableStateOf(initialText) }
    var selectedTextColor by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) } // null = mặc định Cyan

    val textColors = listOf(
        Pair("Cyan", Triple(0, 220, 255)),
        Pair("Đỏ", Triple(255, 30, 30)),
        Pair("Vàng", Triple(255, 215, 0)),
        Pair("Xanh lá", Triple(20, 240, 70)),
        Pair("Tím", Triple(200, 50, 255)),
        Pair("Trắng", Triple(255, 255, 255))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                            text = "Nội dung sẽ cuộn trôi liên tục quanh thân đèn",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("Nội dung tin nhắn") },
                    placeholder = { Text("Nhập chữ cần hiển thị...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )

                // Tùy biến màu chữ
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
                                        .size(10.dp)
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
                        onSendText(textValue, rgb?.first, rgb?.second, rgb?.third)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Bắn chữ lên đèn", fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Tin nhắn mẫu một chạm",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Pair("CHUC MUNG NAM MOI", "Năm mới"),
                Pair("HAPPY BIRTHDAY", "Sinh nhật"),
                Pair("WELCOME HOME", "Về nhà"),
                Pair("COFFEE TIME", "Cà phê")
            ).forEach { (msg, label) ->
                SuggestionChip(
                    onClick = {
                        textValue = msg
                        val rgb = selectedTextColor
                        onSendText(msg, rgb?.first, rgb?.second, rgb?.third)
                    },
                    label = { Text(label) },
                    shape = RoundedCornerShape(12.dp)
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
        // Mode switch pills
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (canvasModeTab == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { canvasModeTab = 0 }
                ) {
                    Text(
                        text = "4 Mặt Độc Lập (8x8)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (canvasModeTab == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (canvasModeTab == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = TextAlign.Center
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (canvasModeTab == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { canvasModeTab = 1 }
                ) {
                    Text(
                        text = "Canvas 32x8 Toàn Cảnh",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (canvasModeTab == 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (canvasModeTab == 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
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
                    key(updateCounter) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            for (y in 0 until 8) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    for (sIdx in 0 until 4) {
                                        for (x in 0 until 8) {
                                            val px = slots[sIdx].getPixel(x, y)
                                            val col = if (px == android.graphics.Color.BLACK) MaterialTheme.colorScheme.surfaceVariant else Color(px)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .background(col)
                                            )
                                        }
                                        if (sIdx < 3) {
                                            Box(
                                                modifier = Modifier
                                                    .width(2.dp)
                                                    .aspectRatio(0.25f)
                                                    .background(MaterialTheme.colorScheme.outlineVariant)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                Box(modifier = Modifier.padding(10.dp)) {
                    key(updateCounter, selectedSlotIndex) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (y in 0 until 8) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    for (x in 0 until 8) {
                                        val px = activeSlot.getPixel(x, y)
                                        val displayColor = if (px == android.graphics.Color.BLACK) MaterialTheme.colorScheme.surfaceVariant else Color(px)

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(displayColor)
                                                .clickable {
                                                    val newC = if (isEraserMode) android.graphics.Color.BLACK else selectedColor
                                                    activeSlot.setPixel(x, y, newC)
                                                    updateCounter++
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(text = "Gửi Ô ${activeSlot.id}")
                }

                Button(
                    onClick = { onSendAllSlots(slots) },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp),
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
                Box(modifier = Modifier.padding(10.dp)) {
                    key(updateCounter) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            for (y in 0 until 8) {
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    for (x in 0 until 32) {
                                        val px = fullCanvas.getPixel(x, y)
                                        val col = if (px == android.graphics.Color.BLACK) MaterialTheme.colorScheme.surfaceVariant else Color(px)

                                        if (x > 0 && x % 8 == 0) {
                                            Box(
                                                modifier = Modifier
                                                    .width(3.dp)
                                                    .height(28.dp)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(col)
                                                .clickable {
                                                    val newC = if (isEraserMode) android.graphics.Color.BLACK else selectedColor
                                                    fullCanvas.setPixel(x, y, newC)
                                                    updateCounter++
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Bắn toàn bộ Canvas 32x8 lên đèn", fontWeight = FontWeight.Bold)
            }
        }
    }
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
    onActivateBedtimeRoutine: () -> Unit
) {
    val context = LocalContext.current

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
                    // Bấm để mở TimePickerDialog chọn giờ
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable {
                            TimePickerDialog(
                                context,
                                { _, h, m ->
                                    onUpdateRoutine(routineConfig.copy(sunriseHour = h, sunriseMinute = m))
                                },
                                routineConfig.sunriseHour,
                                routineConfig.sunriseMinute,
                                true
                            ).show()
                        }
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
                    // Bấm để mở TimePickerDialog chọn giờ ngủ
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable {
                            TimePickerDialog(
                                context,
                                { _, h, m ->
                                    onUpdateRoutine(routineConfig.copy(bedtimeHour = h, bedtimeMinute = m))
                                },
                                routineConfig.bedtimeHour,
                                routineConfig.bedtimeMinute,
                                true
                            ).show()
                        }
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
            }
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
    onTriggerPomodoro: () -> Unit,
    onSyncNow: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onPickFirmwareFile: () -> Unit
) {
    val context = LocalContext.current
    var editingAppConfig by remember { mutableStateOf<AppNotificationConfig?>(null) }
    var showAddAppDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // CẢM BIẾN MPU-6050 & POMODORO
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
                            text = "Góc nghiêng MPU-6050 & Trọng lực",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Pitch: ${telemetry.pitch.toInt()}° | Roll: ${telemetry.roll.toInt()}°",
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
                            onTriggerPomodoro()
                            android.widget.Toast.makeText(context, "Đã gửi lệnh kích hoạt Pomodoro 25p", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pomodoro 25p")
                    }

                    OutlinedButton(
                        onClick = {
                            onSyncNow()
                            android.widget.Toast.makeText(context, "Đang đồng bộ Giờ & Thời tiết xuống đèn...", android.widget.Toast.LENGTH_SHORT).show()
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

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { showAddAppDialog = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Thêm app", modifier = Modifier.size(20.dp))
                        }

                        OutlinedButton(
                            onClick = onOpenNotificationSettings,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
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
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilledTonalIconButton(
                                        onClick = { onTestFlash(app.r, app.g, app.b, app.flashCount) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(Icons.Filled.FlashOn, contentDescription = "Thử chớp", modifier = Modifier.size(16.dp))
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
                                android.widget.Toast.makeText(context, "Vui lòng kết nối Bluetooth tới đèn trước!", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                bleManager.sendCommand("ota")
                                android.widget.Toast.makeText(context, "Đã gửi lệnh vào chế độ OTA", android.widget.Toast.LENGTH_SHORT).show()
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
                                android.widget.Toast.makeText(context, "Vui lòng kết nối Bluetooth tới đèn trước!", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                bleManager.sendCommand("ota abort")
                                android.widget.Toast.makeText(context, "Đã gửi lệnh hủy OTA", android.widget.Toast.LENGTH_SHORT).show()
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
                            android.widget.Toast.makeText(context, "Vui lòng kết nối Bluetooth tới đèn trước!", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            onPickFirmwareFile()
                        }
                    },
                    enabled = progress == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
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
                    Text(
                        text = "${logs.size} sự kiện",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
