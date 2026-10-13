package com.esp32.ledmatrix360.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esp32.ledmatrix360.ble.BleManager
import com.esp32.ledmatrix360.model.*

@Composable
fun HeaderBar(
    isConnected: Boolean,
    isScanning: Boolean,
    onConnectClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(CyanNeon, BlueAccent))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = "Logo đèn LED 360",
                    tint = Color(0xFF06101E)
                )
            }
            Column {
                Text(
                    text = "LED Lamp 360°",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextLight
                )
                Text(
                    text = "ESP32 Bluetooth LE",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }

        Button(
            onClick = onConnectClick,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) GreenJade.copy(alpha = 0.2f) else CyanNeon.copy(alpha = 0.15f),
                contentColor = if (isConnected) GreenJade else CyanNeon
            ),
            shape = RoundedCornerShape(24.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = Brush.linearGradient(listOf(if (isConnected) GreenJade else CyanNeon, BlueAccent))
            )
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) GreenJade else (if (isScanning) AmberWarm else CyanNeon))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isConnected) "Đã kết nối" else (if (isScanning) "Đang tìm..." else "Kết nối"),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun TelemetryBar(state: TelemetryState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCard(
            title = "Nguồn Pin",
            value = when (state.powerState) {
                1 -> "⚡ ĐANG SẠC"
                2 -> "✓ PIN ĐẦY"
                else -> "🔋 DÙNG PIN"
            },
            color = when (state.powerState) {
                1 -> AmberWarm
                2 -> GreenJade
                else -> CyanNeon
            },
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = "Điện áp STAT",
            value = "${state.statMv} mV",
            color = TextLight,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = "Chế độ",
            value = state.modeName,
            color = BlueAccent,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Column {
            Text(text = title, fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(3.dp))
            Text(text = value, fontSize = 13.sp, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BrightnessPowerCard(
    brightness: Int,
    onBrightnessChange: (Int) -> Unit,
    onPowerToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(CardBorder, CardBorder)))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Brightness6, contentDescription = null, tint = CyanNeon)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Độ sáng & Nguồn", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 15.sp)
                }
                IconButton(
                    onClick = onPowerToggle,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(CyanNeon.copy(alpha = 0.15f))
                        .border(1.dp, CyanNeon, CircleShape)
                ) {
                    Icon(imageVector = Icons.Default.PowerSettingsNew, contentDescription = "Bật hoặc Tắt đèn", tint = CyanNeon)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Mức ánh sáng", fontSize = 12.sp, color = TextMuted)
                Text(text = "${brightness} / 255", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
            }

            var localBrightness by remember(brightness) { mutableFloatStateOf(brightness.toFloat()) }
            var lastSentTime by remember { mutableLongStateOf(0L) }

            Slider(
                value = localBrightness,
                onValueChange = { newVal ->
                    localBrightness = newVal
                    val now = System.currentTimeMillis()
                    if (now - lastSentTime > 60L) {
                        lastSentTime = now
                        onBrightnessChange(newVal.toInt())
                    }
                },
                onValueChangeFinished = {
                    onBrightnessChange(localBrightness.toInt())
                },
                valueRange = 5f..255f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = CyanNeon,
                    inactiveTrackColor = Color(0x33FFFFFF)
                )
            )
        }
    }
}

@Composable
fun ModesGrid(
    currentMode: Int,
    onModeSelect: (Int) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(LampConstants.MODES) { mode ->
            ModeItem(
                mode = mode,
                isSelected = mode.id == currentMode,
                onClick = { onModeSelect(mode.id) }
            )
        }
    }
}

@Composable
fun ModeItem(
    mode: LampMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(if (isSelected) CyanNeon else CardBorder, label = "borderColor")
    val bgGradient = if (isSelected) {
        Brush.linearGradient(listOf(CyanNeon.copy(alpha = 0.18f), CardBg))
    } else {
        Brush.linearGradient(listOf(CardBg, CardBg))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgGradient)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Chọn hiệu ứng ${mode.name}", onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            Text(text = mode.icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = mode.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextLight)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = mode.description, fontSize = 11.sp, color = TextMuted, maxLines = 1)
        }
    }
}

@Composable
fun ColorPaletteSection(
    onColorSelected: (Int, Int, Int) -> Unit
) {
    var hue by remember { mutableStateOf(180f) }
    var brightnessFactor by remember { mutableStateOf(1.0f) }

    val selectedColor = remember(hue, brightnessFactor) {
        val hsv = floatArrayOf(hue, 1f, brightnessFactor)
        val androidColor = android.graphics.Color.HSVToColor(hsv)
        Color(androidColor)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Chọn màu RGB tùy biến tức thì", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(selectedColor)
                .clickable {
                    val r = (selectedColor.red * 255).toInt()
                    val g = (selectedColor.green * 255).toInt()
                    val b = (selectedColor.blue * 255).toInt()
                    onColorSelected(r, g, b)
                },
            contentAlignment = Alignment.Center
        ) {
            val r = (selectedColor.red * 255).toInt()
            val g = (selectedColor.green * 255).toInt()
            val b = (selectedColor.blue * 255).toInt()
            Text(
                text = "RGB($r, $g, $b) - Chạm để áp dụng",
                color = if (brightnessFactor > 0.5f && (hue in 40f..180f)) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Sắc độ (Hue: ${hue.toInt()}°)", fontSize = 13.sp, color = TextMuted)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Red, Color.Yellow, Color.Green,
                            Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                        )
                    )
                )
        )
        var lastHueSentTime by remember { mutableLongStateOf(0L) }
        Slider(
            value = hue,
            onValueChange = {
                hue = it
                val now = System.currentTimeMillis()
                if (now - lastHueSentTime > 60L) {
                    lastHueSentTime = now
                    val hsv = floatArrayOf(hue, 1f, brightnessFactor)
                    val c = android.graphics.Color.HSVToColor(hsv)
                    onColorSelected(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
                }
            },
            onValueChangeFinished = {
                val hsv = floatArrayOf(hue, 1f, brightnessFactor)
                val c = android.graphics.Color.HSVToColor(hsv)
                onColorSelected(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
            },
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Bảng màu tinh chọn nhanh", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(10.dp))

        LampConstants.PRESET_COLORS.chunked(3).forEach { rowPresets ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowPresets.forEach { preset ->
                    val isDark = (0.299 * preset.r + 0.587 * preset.g + 0.114 * preset.b) < 140
                    val textColor = if (isDark) Color.White else Color(0xFF0F172A)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(preset.color)
                            .clickable(onClickLabel = "Chọn màu ${preset.name}") {
                                onColorSelected(preset.r, preset.g, preset.b)
                            }
                            .padding(8.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Text(
                            text = preset.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }
                }
                repeat(3 - rowPresets.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MarqueeSection(
    onSendText: (String) -> Unit
) {
    var textValue by remember { mutableStateOf("LED MATRIX 360") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "🔤 Chạy chữ ma trận 360°", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = "Nhập câu chúc hoặc thông báo để cuộn tròn xoay quanh thân đèn 360°", fontSize = 12.sp, color = TextMuted)

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            label = { Text("Nội dung tin nhắn") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanNeon,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = TextLight,
                unfocusedTextColor = TextLight
            ),
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = { onSendText(textValue) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF051329))
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Bắn chữ lên đèn", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(text = "Tin nhắn mẫu 1 chạm:", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = { textValue = "CHUC MUNG NAM MOI"; onSendText("CHUC MUNG NAM MOI") }, label = { Text("🎉 NAM MOI") })
                SuggestionChip(onClick = { textValue = "HAPPY BIRTHDAY"; onSendText("HAPPY BIRTHDAY") }, label = { Text("🎂 BIRTHDAY") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = { textValue = "WELCOME HOME"; onSendText("WELCOME HOME") }, label = { Text("🏠 WELCOME") })
                SuggestionChip(onClick = { textValue = "COFFEE TIME"; onSendText("COFFEE TIME") }, label = { Text("☕ COFFEE") })
            }
        }
    }
}

@Composable
fun PixelArtCanvasSection(
    onSendCanvas: (PixelCanvasState) -> Unit
) {
    val canvasState = remember { PixelCanvasState(32, 8) }
    var selectedColor by remember { mutableStateOf(android.graphics.Color.CYAN) }
    var isEraserMode by remember { mutableStateOf(false) }

    val colors = listOf(
        android.graphics.Color.CYAN,
        android.graphics.Color.RED,
        android.graphics.Color.GREEN,
        android.graphics.Color.YELLOW,
        android.graphics.Color.MAGENTA,
        android.graphics.Color.WHITE,
        android.graphics.Color.BLUE
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "🎨 Trình vẽ Pixel Art 32x8 (256 LED)", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Chấm màu trực tiếp lên mảng 32 cột x 8 hàng để vẽ icon 360°", fontSize = 12.sp, color = TextMuted)

        Spacer(modifier = Modifier.height(12.dp))

        // Khung hiển thị ma trận 32x8
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF040811))
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (y in 0 until 8) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (x in 0 until 32) {
                            var pxColor by remember { mutableStateOf(canvasState.getPixel(x, y)) }
                            val displayColor = if (pxColor == android.graphics.Color.BLACK) Color(0xFF131C2E) else Color(pxColor)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(displayColor)
                                    .clickable {
                                        val newC = if (isEraserMode) android.graphics.Color.BLACK else selectedColor
                                        canvasState.setPixel(x, y, newC)
                                        pxColor = newC
                                    }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Palette chọn màu
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(if (selectedColor == c && !isEraserMode) 2.dp else 0.dp, Color.White, CircleShape)
                            .clickable {
                                selectedColor = c
                                isEraserMode = false
                            }
                    )
                }
            }

            IconButton(
                onClick = { isEraserMode = !isEraserMode },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isEraserMode) CyanNeon.copy(alpha = 0.3f) else CardBg)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Tẩy", tint = if (isEraserMode) CyanNeon else TextMuted)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onSendCanvas(canvasState) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF040811))
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Hiển thị lên đèn", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { canvasState.clear() },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Xóa hết", color = TextMuted)
            }
        }
    }
}

@Composable
fun WeatherSection(
    weatherData: WeatherData,
    onCitySelected: (String) -> Unit,
    onSyncWeatherNow: () -> Unit,
    onPreviewModeSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "🌤️ API Thời tiết & Hoạt hình Đèn", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Kéo dữ liệu thời tiết thời gian thực và tự đổi màu/hoạt hình ma trận theo thời tiết", fontSize = 12.sp, color = TextMuted)

        Spacer(modifier = Modifier.height(14.dp))

        // Selector Thành phố
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, tint = CyanNeon)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Thành phố: ${weatherData.cityName}", color = TextLight, fontWeight = FontWeight.Bold)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                LampConstants.CITIES.forEach { city ->
                    DropdownMenuItem(
                        text = { Text(text = city.first) },
                        onClick = {
                            onCitySelected(city.first)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Card Thời tiết hiện tại
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(18.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(CyanNeon, BlueAccent)))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = weatherData.cityName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextLight)
                        Text(text = weatherData.weatherName, fontSize = 13.sp, color = CyanNeon)
                    }
                    Text(text = "${weatherData.weatherIcon} ${weatherData.temp}°C", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextLight)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onSyncWeatherNow,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF040811))
                ) {
                    Icon(imageVector = Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Đồng bộ thời tiết ngay xuống đèn", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(text = "Bảng hoạt hình tương ứng thời tiết:", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(10.dp))

        val weatherModes = listOf(
            Triple("☀️ Trời Nắng", "Mode 1: Lava Noise", 1),
            Triple("☁️ Mây / Sương mù", "Mode 2: Edge Light", 2),
            Triple("🌧️ Mưa rào", "Mode 5: Water Physics", 5),
            Triple("🌩️ Sấm chớp", "Mode 4: Tilt Glow", 4)
        )

        weatherModes.forEach { (title, modeText, modeId) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onPreviewModeSelect(modeId) },
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = title, fontWeight = FontWeight.Bold, color = TextLight, fontSize = 13.sp)
                        Text(text = modeText, color = CyanNeon, fontSize = 11.sp)
                    }
                    Text(text = "Thử ngay >", color = TextMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun NotificationMappingSection(
    configs: List<AppNotificationConfig>,
    onConfigToggle: (String, Boolean) -> Unit,
    onTestFlash: (Int, Int, Int) -> Unit,
    onOpenPermissionSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(CyanNeon.copy(alpha = 0.5f), BlueAccent)))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null, tint = CyanNeon)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Đồng bộ Thông báo Hệ thống", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Khi điện thoại nhận thông báo, đèn sẽ tự động chớp màu sắc tương ứng để bạn không bao giờ bỏ lỡ tin nhắn quan trọng.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onOpenPermissionSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF051329)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Mở Cài đặt Cấp quyền Thông báo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Cấu hình màu sắc từng ứng dụng", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(10.dp))

        configs.forEach { app ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.rgb(app.r, app.g, app.b)).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = app.iconEmoji, fontSize = 16.sp)
                        }

                        Column {
                            Text(text = app.appName, fontWeight = FontWeight.Bold, color = TextLight, fontSize = 13.sp)
                            Text(text = "RGB(${app.r}, ${app.g}, ${app.b})", color = Color(android.graphics.Color.rgb(app.r, app.g, app.b)), fontSize = 11.sp)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onTestFlash(app.r, app.g, app.b) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(text = "Thử chớp", fontSize = 12.sp, color = CyanNeon, fontWeight = FontWeight.SemiBold)
                        }

                        Switch(
                            checked = app.isEnabled,
                            onCheckedChange = { onConfigToggle(app.packageName, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = CyanNeon.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SensorSection(
    state: TelemetryState,
    logs: List<String>,
    onTriggerPomodoro: () -> Unit,
    onSyncNow: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Mô phỏng góc nghiêng Pitch & Roll
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(CyanNeon, BlueAccent)))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(text = "🎯 Mô phỏng góc nghiêng MPU-6050", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Pitch (Gật): ${state.pitch.toInt()}°", color = CyanNeon, fontWeight = FontWeight.Bold)
                        Text(text = "Roll (Lắc): ${state.roll.toInt()}°", color = PinkCyber, fontWeight = FontWeight.Bold)
                        Text(text = "RAM Heap: ${state.heap / 1024} KB", color = GreenJade, fontSize = 12.sp)
                    }

                    // Hình trụ mini trực quan nghiêng
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0B1426))
                            .border(1.dp, CyanNeon, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(CyanNeon)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Điều khiển Pomodoro 25p từ xa
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "⏱️ Đồng hồ Pomodoro 25 Phút", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 14.sp)
                        Text(text = "Kích hoạt đếm lùi thời gian tập trung làm việc", fontSize = 11.sp, color = TextMuted)
                    }

                    Button(
                        onClick = onTriggerPomodoro,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberWarm, contentColor = Color.Black),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = "Bắt đầu 25p", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Công cụ đồng bộ & pin
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSyncNow,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(CyanNeon, BlueAccent)))
            ) {
                Icon(imageVector = Icons.Default.Sync, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Đồng bộ Giờ/Thời tiết", fontSize = 11.sp, color = TextLight)
            }

            Button(
                onClick = onRequestBatteryOptimization,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.BatteryChargingFull, contentDescription = null, tint = AmberWarm, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Bỏ tối ưu Pin", fontSize = 11.sp, color = TextLight)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Nhật ký hệ thống & Gói tin BLE", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF040811))
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            LazyColumn {
                items(logs) { log ->
                    Text(text = log, color = Color(0xFF38BDF8), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun OtaSection(
    bleManager: BleManager,
    onPickFirmwareFile: () -> Unit
) {
    val isConnected by bleManager.isConnected.collectAsState()
    val progress by bleManager.otaProgress.collectAsState()
    val statusMsg by bleManager.otaStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(CyanNeon, BlueAccent))),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.SystemUpdate, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Cập nhật Firmware qua Bluetooth (OTA)", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Cách vào chế độ OTA trên đèn:\n" +
                           "• Cách 1: Giữ nút cảm ứng trên đèn trong 3 giây (đặt đèn cố định trên bàn).\n" +
                           "• Cách 2: Bấm nút 'Vào chế độ OTA' bên dưới khi đã kết nối Bluetooth.",
                    color = TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Thao tác nạp Firmware (.bin)", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 15.sp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { bleManager.sendCommand("ota") },
                        enabled = isConnected && progress == null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon.copy(alpha = 0.2f), contentColor = CyanNeon),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "Vào chế độ OTA", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { bleManager.sendCommand("ota abort") },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PinkCyber.copy(alpha = 0.2f), contentColor = PinkCyber),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "Hủy OTA", fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = onPickFirmwareFile,
                    enabled = isConnected && progress == null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF040811)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Chọn file firmware.bin để nạp", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                if (progress != null || statusMsg.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = statusMsg, color = CyanNeon, fontSize = 12.sp)
                    progress?.let { p ->
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = CyanNeon,
                            trackColor = CardBorder
                        )
                    }
                }
            }
        }
    }
}
