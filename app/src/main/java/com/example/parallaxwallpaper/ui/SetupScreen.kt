package com.example.parallaxwallpaper.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.parallaxwallpaper.depth.DepthEstimator
import com.example.parallaxwallpaper.wallpaper.ParallaxWallpaperService
import kotlinx.coroutines.delay

@Composable
fun SetupScreen(modifier: Modifier = Modifier, vm: SetupViewModel = viewModel()) {
    val settings    by vm.settings.collectAsState()
    val copying     by vm.imageCopying.collectAsState()
    val modelReady  = vm.modelReady
    val context     = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.pickImage(it) }
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); visible = true }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0F0C29), Color(0xFF302B63), Color(0xFF24243E))
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { -40 }) {
            Column {
                Text(
                    "Parallax Wallpaper",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                )
                Text(
                    "Depth Anything V2 · OpenGL ES 2.0 · Gyroscope",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFBBBBCC))
                )
            }
        }

        // ── Model status banner ─────────────────────────────────────────────
        AnimatedVisibility(visible, enter = fadeIn()) {
            ModelStatusBanner(modelReady)
        }

        // ── Image picker card ───────────────────────────────────────────────
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { 60 }) {
            SettingsCard("Source Image", Icons.Default.Image) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF6650A4), RoundedCornerShape(12.dp))
                        .clickable { imagePicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (settings.imagePath != null && !copying) {
                        AsyncImage(
                            model = settings.imagePath,
                            contentDescription = "Wallpaper preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    } else if (copying) {
                        CircularProgressIndicator(color = Color(0xFF9E86FF))
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, null, tint = Color(0xFF9E86FF), modifier = Modifier.size(48.dp))
                            Text("Tap to choose a photo", color = Color(0xFF9E86FF), fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // ── Parallax strength ───────────────────────────────────────────────
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { 80 }) {
            SettingsCard("Parallax Strength", Icons.Default.Layers) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Subtle",   color = Color(0xFFBBBBCC), fontSize = 12.sp)
                        Text("%.0f%%".format(settings.parallaxStrength * 2500),
                            color = Color(0xFFD0BCFF), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Dramatic", color = Color(0xFFBBBBCC), fontSize = 12.sp)
                    }
                    Slider(
                        value = settings.parallaxStrength,
                        onValueChange = vm::setParallaxStrength,
                        valueRange = 0.01f..0.12f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFD0BCFF),
                            activeTrackColor = Color(0xFF6650A4)
                        )
                    )
                }
            }
        }

        // ── Frame rate ──────────────────────────────────────────────────────
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { 100 }) {
            SettingsCard("Frame Rate", Icons.Default.Speed) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (fps in listOf(30, 60, 90)) {
                        val selected = settings.targetFps == fps
                        OutlinedButton(
                            onClick = { vm.setTargetFps(fps) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) Color(0xFF6650A4) else Color.Transparent,
                                contentColor   = if (selected) Color.White else Color(0xFFBBBBCC)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, if (selected) Color(0xFF9E86FF) else Color(0xFF444466)
                            )
                        ) { Text("$fps fps") }
                    }
                }
            }
        }

        // ── NNAPI toggle ────────────────────────────────────────────────────
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { 120 }) {
            SettingsCard("Inference Acceleration", Icons.Default.Memory) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("NNAPI (Neural Networks API)",
                            color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Hardware-accelerated depth on supported devices",
                            color = Color(0xFFBBBBCC), fontSize = 11.sp)
                    }
                    Switch(
                        checked = settings.useNnapi,
                        onCheckedChange = vm::setUseNnapi,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFD0BCFF),
                            checkedTrackColor = Color(0xFF6650A4)
                        )
                    )
                }
            }
        }

        // ── Set wallpaper ───────────────────────────────────────────────────
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { 140 }) {
            val canSet = modelReady && settings.imagePath != null && !copying
            Button(
                onClick = {
                    context.startActivity(
                        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                            putExtra(
                                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(context, ParallaxWallpaperService::class.java)
                            )
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6650A4)),
                enabled = canSet
            ) {
                Icon(Icons.Default.Wallpaper, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !modelReady          -> "Model not found – see README"
                        settings.imagePath == null -> "Pick an image first"
                        else                 -> "Set as Live Wallpaper"
                    },
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun ModelStatusBanner(modelReady: Boolean) {
    val (bg, icon, tint, text) = if (modelReady) {
        listOf(
            Color(0xFF1A2E1A),
            Icons.Default.CheckCircle,
            Color(0xFF66BB6A),
            "Model ready: ${DepthEstimator.MODEL_ASSET}"
        )
    } else {
        listOf(
            Color(0xFF2E1A1A),
            Icons.Default.Warning,
            Color(0xFFFF8A65),
            "Model missing – run scripts/download_model.ps1 (Windows) or download_model.sh, " +
            "then place ${DepthEstimator.MODEL_ASSET} in app/src/main/assets/"
        )
    }

    @Suppress("UNCHECKED_CAST")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg as Color
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon as ImageVector, null, tint = tint as Color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Text(text as String, color = Color(0xFFDDDDDD), fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1E1E3A), tonalElevation = 4.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = Color(0xFF9E86FF), modifier = Modifier.size(18.dp))
                Text(title, color = Color(0xFFD0BCFF), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            content()
        }
    }
}
