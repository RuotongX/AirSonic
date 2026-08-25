// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings as SysSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.content.Context
import com.airsonic.demo.BuildConfig
import com.airsonic.sender.api.AirDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airsonic.demo.ui.theme.Aurora
import com.airsonic.demo.ui.theme.glass

/** Activity 提供的需要系统能力的动作。 */
class CastActions(
    val requestSystemAudioCast: () -> Unit,
    val requestScreenMirrorCast: () -> Unit,
    val openDebug: () -> Unit,
    val requestHttpStream: () -> Unit,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PinDialogHost() {
    val deviceName = CastEngine.pinRequest.value ?: return
    var pin by remember(CastEngine.pinNonce.value) { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { CastEngine.cancelPin() },
        title = { Text("${L10n.s.pinTitle} · $deviceName", color = Aurora.TextPrimary) },
        text = {
            Column {
                Text(L10n.s.pinHint, color = Aurora.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Aurora.TextPrimary, fontSize = 22.sp),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (pin.length == 4) CastEngine.submitPin(pin) },
                enabled = pin.length == 4,
            ) { Text(L10n.s.confirm, color = Aurora.Cyan) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { CastEngine.cancelPin() }) {
                Text(L10n.s.cancel, color = Aurora.TextDim)
            }
        },
        containerColor = Aurora.Surface,
    )
}

@Composable
fun AppNav(actions: CastActions) {
    val nav = rememberNavController()
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "splash") {
            composable("splash") { SplashScreen { nav.navigate("main") { popUpTo("splash") { inclusive = true } } } }
            composable("main") { MainScreen(nav) }
            composable("mirror") { MirrorScreen(nav, actions) }
            composable("audio") { MediaListScreen(nav, isVideo = false) }
            composable("video") { MediaListScreen(nav, isVideo = true) }
            composable("settings") { SettingsScreen(nav, actions) }
            composable("protocols") { ProtocolsScreen(nav) }
            composable("legal") { LegalScreen(nav) }
        }
        PinDialogHost()
    }
}

// ============ 开屏 ============
@Composable
fun SplashScreen(onDone: () -> Unit) {
    var remain by remember { mutableStateOf(6) }
    LaunchedEffect(Unit) {
        while (remain > 0) { kotlinx.coroutines.delay(1000); remain-- }
        onDone()
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AirSonicLogo(modifier = Modifier.size(160.dp), animated = true)
            Spacer(Modifier.height(20.dp))
            Text("AirSonic", color = Aurora.TextPrimary, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(L10n.s.tagline, color = Aurora.TextSecondary, fontSize = 14.sp)
        }
        // 跳过倒计时（右上角，可点立即跳过）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .glass(radius = 18)
                .clickable { onDone() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("${L10n.s.skip} ${remain}s", color = Aurora.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ============ 主页 ============
@Composable
fun MainScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { CastEngine.startDiscovery(ctx) }
    var showDevices by remember { mutableStateOf(false) }
    val selected by CastEngine.selected
    val phase by CastEngine.phase
    val s = L10n.s

    BackHandler(enabled = showDevices) { showDevices = false }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .blur(if (showDevices) 18.dp else 0.dp)   // 下拉设备时背景磨砂
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 24.dp)
        ) {
            // 顶部：设备选择 + 设置
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false)   // 长设备名不许挤掉设置按钮
                        .glass(radius = 22).clickable { showDevices = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Cast, null, tint = Aurora.Cyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        selected?.let { DevicePrefs.displayName(ctx, it) } ?: s.selectDevice,
                        color = Aurora.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.ExpandMore, null, tint = Aurora.TextSecondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).glass(radius = 22).clickable { nav.navigate("settings") },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Settings, s.settings, tint = Aurora.TextSecondary, modifier = Modifier.size(22.dp)) }
            }

            Spacer(Modifier.height(22.dp))
            MirrorHeroCard(animated = phase == CastPhase.CASTING) { nav.navigate("mirror") }

            Spacer(Modifier.height(26.dp))
            SectionTitle(s.localMedia)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EntryCard(Icons.Rounded.Videocam, s.video, Color(0xFF4A8CFF), Modifier.weight(1f)) { nav.navigate("video") }
                EntryCard(Icons.Rounded.LibraryMusic, s.audio, Color(0xFFFF9F40), Modifier.weight(1f)) { nav.navigate("audio") }
            }
        }

        // 设备选择浮层（背景磨砂 + 半透遮罩）
        if (showDevices) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showDevices = false }
            )
            Box(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .padding(top = 78.dp, start = 20.dp, end = 20.dp)
            ) {
                DevicePanel { showDevices = false }
            }
        }
    }
}

/** 大「屏幕镜像」hero 卡 —— AirSonic 自有品牌：青→品红渐变 + 原创声波标识。 */
@Composable
private fun MirrorHeroCard(animated: Boolean, onClick: () -> Unit) {
    // 用 Row + weight 给文字预留独立宽度，标识用固定宽度——避免长副标题跑到标识下方重合。
    // 高度自适应（min 150）：英文文案比中文长，定高会把换行后的副标题裁掉。
    Row(
        Modifier.fillMaxWidth().heightIn(min = 150.dp)
            .shadow(16.dp, RoundedCornerShape(28.dp), clip = false,
                ambientColor = Aurora.Magenta.copy(alpha = 0.5f), spotColor = Aurora.Cyan.copy(alpha = 0.5f))
            .background(Aurora.brandBrush, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(L10n.s.mirrorTitle, color = Color(0xFF071018), fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(L10n.s.mirrorSub, color = Color(0xCC071018), fontSize = 13.sp)
        }
        Spacer(Modifier.width(14.dp))
        // 原创 AirSonic 声波标识（深色，叠在品牌渐变上）
        AirSonicLogo(
            modifier = Modifier.size(84.dp),
            animated = animated,
            color = Color(0xFF071018),
        )
    }
}

/** 功能入口卡（彩色图标磁贴 + 标签）。 */
@Composable
private fun EntryCard(icon: ImageVector, label: String, iconColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.aspectRatio(1.15f).glass(radius = 22).clickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier.size(52.dp).background(iconColor, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Text(label, color = Aurora.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ============ app 内媒体浏览器（音频 / 视频）============
private fun mediaPermission(isVideo: Boolean): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        (if (isVideo) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_AUDIO)
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun MediaListScreen(nav: NavHostController, isVideo: Boolean) {
    val ctx = LocalContext.current
    val perm = mediaPermission(isVideo)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var items by remember { mutableStateOf<List<MediaLibrary.Item>>(emptyList()) }
    val s = L10n.s
    LaunchedEffect(granted) {
        if (granted) items = withContext(Dispatchers.IO) {
            if (isVideo) MediaLibrary.queryVideo(ctx) else MediaLibrary.queryAudio(ctx)
        }
    }
    ScreenScaffold(nav, if (isVideo) s.castVideo else s.castAudio) {
        when {
            !granted -> {
                Box(Modifier.fillMaxWidth().glass(radius = 18).padding(20.dp)) {
                    Column {
                        Text(s.needMedia, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(s.grantDesc, color = Aurora.TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(s.grant) { launcher.launch(perm) }
                    }
                }
            }
            items.isEmpty() -> Text(if (isVideo) s.noVideo else s.noAudio, color = Aurora.TextDim, fontSize = 14.sp)
            else -> items.forEach { item ->
                MediaRow(item) {
                    if (isVideo) CastEngine.startVideoCast(ctx, item.uri)
                    else CastEngine.startFileCast(ctx, item.uri)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        CastZone()
    }
}

@Composable
private fun MediaRow(item: MediaLibrary.Item, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) { MediaLibrary.thumbnail(ctx, item) }
    }
    Row(
        Modifier.fillMaxWidth().glass(radius = 16).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(11.dp)).background(Aurora.Surface),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) Image(bitmap = t.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            else Icon(if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.MusicNote, null, tint = Aurora.Cyan, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Aurora.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(item.durationText)
                    if (item.resText.isNotEmpty()) append("  ·  ${item.resText}")
                },
                color = Aurora.TextDim, fontSize = 12.sp,
            )
        }
        Icon(Icons.Rounded.Cast, null, tint = Aurora.Cyan, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DevicePanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    DevicePrefs.version.value  // 观察持久化变更触发重组
    var renameTarget by remember { mutableStateOf<AirDevice?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }

    val hidden = CastEngine.devices.filter { DevicePrefs.isHidden(ctx, it) }
    val visible = CastEngine.devices.filter { !DevicePrefs.isHidden(ctx, it) }

    Column(
        Modifier.fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(20.dp), clip = false,
                ambientColor = Aurora.Magenta.copy(alpha = 0.4f), spotColor = Aurora.Cyan.copy(alpha = 0.4f))
            .background(Aurora.Surface.copy(alpha = 0.98f), RoundedCornerShape(20.dp))  // 近实底，文字清晰
            .border(1.dp, Aurora.GlassStroke, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(L10n.s.devicesOnWifi)
            Spacer(Modifier.weight(1f))
            if (hidden.isNotEmpty()) {
                Text("${L10n.s.hiddenDevices} ${hidden.size}",
                    color = if (showHidden) Aurora.Cyan else Aurora.TextDim, fontSize = 13.sp,
                    modifier = Modifier.clickable { showHidden = !showHidden })
                Spacer(Modifier.width(14.dp))
            }
            Text(L10n.s.refresh, color = Aurora.Cyan, fontSize = 13.sp,
                modifier = Modifier.clickable { CastEngine.startDiscovery(ctx) })
        }
        Spacer(Modifier.height(8.dp))
        if (visible.isEmpty() && hidden.isEmpty()) {
            Text(L10n.s.noDevices, color = Aurora.TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        } else {
            visible.forEach { d ->
                val castable = CastEngine.isCastable(d)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(if (castable) Aurora.Cyan else Aurora.TextDim, RoundedCornerShape(50)))
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier.weight(1f).clickable(enabled = castable) { CastEngine.select(d); onClose() }
                    ) {
                        Text(DevicePrefs.displayName(ctx, d), color = if (castable) Aurora.TextPrimary else Aurora.TextDim, fontSize = 15.sp)
                        Text(CastEngine.typeLabel(d), color = Aurora.TextDim, fontSize = 11.sp)
                    }
                    if (CastEngine.selected.value?.id == d.id) {
                        Text("✓", color = Aurora.Cyan, fontSize = 16.sp); Spacer(Modifier.width(6.dp))
                    }
                    Box {
                        Text("⋯", color = Aurora.TextDim, fontSize = 22.sp,
                            modifier = Modifier.clickable { menuFor = d.id }.padding(horizontal = 6.dp))
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuFor == d.id, onDismissRequest = { menuFor = null },
                            modifier = Modifier.background(Aurora.Surface),
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(L10n.s.rename, color = Aurora.TextPrimary) },
                                onClick = { renameTarget = d; menuFor = null })
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(L10n.s.hide, color = Aurora.TextPrimary) },
                                onClick = { DevicePrefs.setHidden(ctx, d, true); menuFor = null })
                        }
                    }
                }
            }
        }
        if (showHidden && hidden.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Aurora.GlassStroke))
            Spacer(Modifier.height(6.dp))
            Text(L10n.s.hiddenDevices, color = Aurora.TextDim, fontSize = 11.sp)
            hidden.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(DevicePrefs.displayName(ctx, d), color = Aurora.TextDim, fontSize = 14.sp)
                        Text(CastEngine.typeLabel(d), color = Aurora.TextDim, fontSize = 11.sp)
                    }
                    Text(L10n.s.unhide, color = Aurora.Cyan, fontSize = 13.sp,
                        modifier = Modifier.clickable { DevicePrefs.setHidden(ctx, d, false) })
                }
            }
        }
    }
    renameTarget?.let { d -> RenameDeviceDialog(ctx, d) { renameTarget = null } }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RenameDeviceDialog(ctx: Context, device: AirDevice, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(DevicePrefs.displayName(ctx, device)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${L10n.s.rename} · ${device.name}", color = Aurora.TextPrimary, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(L10n.s.renameHint, color = Aurora.TextDim) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Aurora.TextPrimary, fontSize = 18.sp),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                DevicePrefs.setCustomName(ctx, device, text.takeIf { it.isNotBlank() && it.trim() != device.name })
                onDismiss()
            }) { Text(L10n.s.confirm, color = Aurora.Cyan) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(L10n.s.cancel, color = Aurora.TextDim) }
        },
        containerColor = Aurora.Surface,
    )
}

@Composable
private fun VideoControlBar(deviceName: String) {
    val pos = CastEngine.videoPos.value.toFloat()
    val dur = CastEngine.videoDur.value.toFloat()
    val paused = CastEngine.videoPaused.value
    var dragPos by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxWidth().glass(radius = 24, strong = true).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${L10n.s.videoOnTv} · $deviceName", color = Aurora.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Slider(
            value = dragPos ?: pos,
            onValueChange = { dragPos = it },
            valueRange = 0f..(if (dur > 0f) dur else 1f),
            onValueChangeFinished = {
                dragPos?.let { CastEngine.videoSeek(it.toDouble()) }
                dragPos = null
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                GlassButton(if (paused) L10n.s.resume else L10n.s.pause) {
                    if (paused) CastEngine.videoResume() else CastEngine.videoPause()
                }
            }
            Box(Modifier.weight(1f)) {
                GlassButton(L10n.s.stop) { CastEngine.stop() }
            }
        }
        if (CastEngine.volumeActive.value) {
            Spacer(Modifier.height(10.dp))
            VolumeRow()
        }
    }
}

// ============ 通用：返回头 + 投送区 ============
@Composable
private fun ScreenScaffold(nav: NavHostController, title: String, content: @Composable () -> Unit) {
    // 进入页面清理上一次的残留状态（避免显示陈旧的"已取消"等）
    LaunchedEffect(title) { if (CastEngine.phase.value != CastPhase.CASTING) CastEngine.statusLine.value = "" }
    Column(
        Modifier.fillMaxSize()
            .padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).glass(radius = 20).clickable { nav.popBackStack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = L10n.s.back, tint = Aurora.TextPrimary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(title, color = Aurora.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}


@Composable
private fun CastZone() {
    val ctx = LocalContext.current
    val phase by CastEngine.phase
    val status by CastEngine.statusLine
    val selected by CastEngine.selected
    val started by CastEngine.startedAt
    val spectrum by CastEngine.spectrum
    // 投送中显示会话锁定的设备名——selected 会被 mDNS 抖动清空/漂到别的设备(看着像"自动投到小米")
    val name = CastEngine.castingDeviceName.value.ifEmpty {
        selected?.let { DevicePrefs.displayName(ctx, it) } ?: L10n.s.device
    }
    Spacer(Modifier.height(18.dp))
    when (phase) {
        CastPhase.CASTING ->
            if (CastEngine.isVideo.value) VideoControlBar(name)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CastingBar(name, started, spectrum) { CastEngine.stop() }
                val codec = CastEngine.activeCodec.value
                if (codec.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("${L10n.s.codecLabel} $codec", color = Aurora.TextDim, fontSize = 11.sp)
                }
                // HTTP 流输出：显示直播地址 + 一键复制
                CastEngine.httpStreamUrl.value?.let { url ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().glass(radius = 12).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(url, color = Aurora.Cyan, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            L10n.s.copyUrl, color = Aurora.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("airsonic", url))
                                android.widget.Toast.makeText(ctx, L10n.s.copied, android.widget.Toast.LENGTH_SHORT).show()
                            }.padding(start = 10.dp),
                        )
                    }
                }
            }
        CastPhase.CONNECTING -> Text(status, color = Aurora.Cyan, fontSize = 14.sp)
        CastPhase.ERROR -> Text(status, color = Aurora.Magenta, fontSize = 14.sp)
        CastPhase.IDLE -> if (status.isNotEmpty()) Text(status, color = Aurora.TextDim, fontSize = 13.sp)
    }
}

// ============ 屏幕镜像（音频优先） ============
@Composable
fun MirrorScreen(nav: NavHostController, actions: CastActions) {
    var infoDialog by remember { mutableStateOf<String?>(null) }
    val s = L10n.s
    val ctx = LocalContext.current
    ScreenScaffold(nav, s.mirrorTitle) {
        // 投画面（整屏）：自研应用内投屏（录屏 H.264→MPEG-TS→DLNA 实时流），屏幕镜像的唯一能力。
        SectionTitleInfo(s.mirrorPicture) { infoDialog = s.mirrorInAppSub }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().glass(radius = 16)
                .clickable { actions.requestScreenMirrorCast() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(s.mirrorInAppTitle, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(s.mirrorInAppSub, color = Aurora.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.background(Aurora.Cyan.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(CastEngine.selected.value?.let { CastEngine.typeLabel(it) } ?: s.castDevice, color = Aurora.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
            Icon(Icons.Rounded.ChevronRight, null, tint = Aurora.Cyan, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(20.dp))
        SectionTitleInfo(s.sound) { infoDialog = s.soundInfo }
        Spacer(Modifier.height(8.dp))
        // 仅投声音：与「投画面」一致，点卡片直接进入投声音逻辑（系统音频→所选音箱）
        Row(
            Modifier.fillMaxWidth().glass(radius = 16)
                .clickable { actions.requestSystemAudioCast() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(s.soundOnly, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(s.soundOnlyDesc, color = Aurora.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.background(Aurora.Cyan.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(CastEngine.selected.value?.let { CastEngine.typeLabel(it) } ?: s.castDevice, color = Aurora.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
            Icon(Icons.Rounded.ChevronRight, null, tint = Aurora.Cyan, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        // HTTP 流输出（AirMusic 式）：不选设备，生成直播地址给任意播放器
        Row(
            Modifier.fillMaxWidth().glass(radius = 16)
                .clickable { actions.requestHttpStream() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(s.httpStreamTitle, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(s.httpStreamSub, color = Aurora.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Aurora.Cyan, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(24.dp))
        CastZone()
        Spacer(Modifier.height(12.dp))
        // 后台保活：按钮跳系统设置；小感叹号与上方一致，点开看完整说明
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.bgKeepAction, color = Aurora.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        runCatching { ctx.startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                    .padding(vertical = 6.dp),
            )
            IconButton(onClick = { infoDialog = s.bgKeepHint }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Info, contentDescription = s.bgKeepAction, tint = Aurora.TextDim, modifier = Modifier.size(16.dp))
            }
        }
        infoDialog?.let { InfoDialog(it) { infoDialog = null } }
    }
}

@Composable
private fun SectionTitleInfo(title: String, onInfo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(title)
        IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.Info, contentDescription = title, tint = Aurora.TextDim, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun InfoDialog(text: String, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onClose) {
                Text(L10n.s.confirm, color = Aurora.Cyan)
            }
        },
        text = { Text(text, color = Aurora.TextDim, fontSize = 14.sp, lineHeight = 20.sp) },
        containerColor = Aurora.Surface,
    )
}

// ============ 设置 ============
@Composable
fun SettingsScreen(nav: NavHostController, actions: CastActions) {
    val ctx = LocalContext.current
    val s = L10n.s
    ScreenScaffold(nav, s.settings) {
        // 语言切换
        Column(Modifier.fillMaxWidth().glass(radius = 16).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).background(Aurora.brandBrush, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Language, null, tint = Color(0xFF00131A), modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(s.language, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Segmented(
                options = listOf("中文", "English"),
                selectedIndex = if (L10n.lang.value == Lang.EN) 1 else 0,
            ) { idx -> L10n.set(ctx, if (idx == 1) Lang.EN else Lang.ZH) }
        }
        Spacer(Modifier.height(12.dp))
        SettingRow(Icons.Rounded.Cast, s.protocolsTitle, s.protocolsSub) { nav.navigate("protocols") }
        SettingRow(Icons.Rounded.Shield, s.legalTitle, s.legalSub) { nav.navigate("legal") }
        SettingRow(Icons.Rounded.Speaker, s.howTitle, s.howSub)
        // 调试区：版本号连点 10 下解锁（普通用户不需要兼容开关，避免误触）
        if (CastEngine.debugUnlocked.value) {
            ForceAlacRow()
            SonosWavRow()
            SettingRow(Icons.Rounded.BugReport, s.debugTitle, s.debugSub, onClick = actions.openDebug)
        }
        UpdateRow()
        Spacer(Modifier.height(16.dp))
        VersionTapRow()
        Spacer(Modifier.height(4.dp))
        Text(
            s.licenseLine,
            color = Aurora.TextDim, fontSize = 10.sp, lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 版本号行：连点 10 下（3 秒窗口内）解锁/收起调试区（调试入口+兼容开关）。 */
@Composable
private fun VersionTapRow() {
    val ctx = LocalContext.current
    val s = L10n.s
    var taps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    var windowStart by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    Text(
        "${s.version} ${BuildConfig.VERSION_NAME}",
        color = Aurora.TextDim, fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - windowStart > 3000) { taps = 0; windowStart = now }
                taps++
                if (taps >= 10) {
                    taps = 0
                    val unlock = !CastEngine.debugUnlocked.value
                    CastEngine.setDebugUnlocked(ctx, unlock)
                    val msg = if (L10n.lang.value == Lang.EN)
                        if (unlock) "Debug section unlocked" else "Debug section hidden"
                    else
                        if (unlock) "调试区已解锁" else "调试区已收起"
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** 「强制 ALAC」开关行：Sonos 等只收 ALAC 的设备打开；持久化，HomePod 不受影响。 */
@Composable
private fun ForceAlacRow() {
    val ctx = LocalContext.current
    val s = L10n.s
    val on = CastEngine.forceAlac.value
    Row(
        Modifier.fillMaxWidth().glass(radius = 16).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(Aurora.brandBrush, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.GraphicEq, null, tint = Color(0xFF00131A), modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.forceAlacTitle, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(s.forceAlacSub, color = Aurora.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = { CastEngine.setForceAlac(ctx, it) })
    }
    Spacer(Modifier.height(12.dp))
}

/** 「Sonos 改投 WAV」开关行：AAC 电台流不出声时的兼容兜底（无压缩 WAV 流）。 */
@Composable
private fun SonosWavRow() {
    val ctx = LocalContext.current
    val s = L10n.s
    val on = CastEngine.sonosWav.value
    Row(
        Modifier.fillMaxWidth().glass(radius = 16).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(Aurora.brandBrush, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Speaker, null, tint = Color(0xFF00131A), modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.sonosWavTitle, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(s.sonosWavSub, color = Aurora.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = { CastEngine.setSonosWav(ctx, it) })
    }
    Spacer(Modifier.height(12.dp))
}

/** 在线更新行：点一下查 GitHub Releases；下载走系统 DownloadManager（切后台不断），下完点击/自动安装。 */
@Composable
private fun UpdateRow() {
    val ctx = LocalContext.current
    val s = L10n.s
    val scope = rememberCoroutineScope()

    // 状态机：idle / checking / uptodate / available / downloading / failed（单例持有，离开页面不丢）
    var status by Updater.uiStatus
    var release by Updater.uiRelease
    var progress by Updater.uiProgress
    var downloadedApk by Updater.uiDownloadedApk

    // 进入时接上在途/已完成的系统下载：下完回来直接可装，不重新下载
    LaunchedEffect(Unit) {
        val st = Updater.downloadStatus(ctx)
        if (st != null) when (st.first) {
            "success" -> if (st.third != null) { downloadedApk = st.third; status = "available" }
            "running" -> status = "downloading"
            else -> Unit
        }
        // 轮询：下载中推进度；前台时完成→自动拉起安装；失败→状态可见
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (status != "downloading") continue
            val cur = Updater.downloadStatus(ctx) ?: continue
            progress = cur.second
            when (cur.first) {
                "success" -> {
                    if (cur.third != null) {
                        downloadedApk = cur.third; status = "available"
                        Updater.installDownloadedApk(ctx, cur.third!!)
                    } else status = "failed"
                }
                "failed" -> status = "failed"
            }
        }
    }

    val title = if (status == "available") s.download else s.checkUpdate
    val sub = when (status) {
        "checking" -> s.checking
        "uptodate" -> "${s.upToDate} · v${Updater.currentVersion}"
        "available" -> if (downloadedApk != null) s.installNow else "${s.newVersion} v${release?.versionName}"
        "downloading" -> "${s.downloading} ${if (progress >= 0) "$progress%" else "…"}"
        "failed" -> s.updateFailed
        else -> "${s.checkUpdateSub} v${Updater.currentVersion}"
    }
    val busy = status == "checking" || status == "downloading"

    val onClick: () -> Unit = onClick@{
        if (busy) return@onClick
        val apk = downloadedApk
        if (status == "available" && apk != null) { Updater.installDownloadedApk(ctx, apk); return@onClick }
        val rel = release
        if (status == "available" && rel != null) {
            val url = rel.apkUrl
            if (url == null) { Updater.openReleasePage(ctx, rel.htmlUrl); return@onClick }
            status = "downloading"; progress = 0
            Updater.enqueueApkDownload(ctx, url)   // LaunchedEffect 的轮询环会接管进度/安装
        } else {
            status = "checking"
            scope.launch {
                val rel2 = Updater.checkLatest()
                when {
                    rel2 == null -> status = "failed"
                    Updater.isNewer(rel2.versionName, Updater.currentVersion) -> {
                        release = rel2; status = "available"
                    }
                    else -> status = "uptodate"
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().glass(radius = 16).clickable(enabled = !busy) { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(Aurora.brandBrush, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF00131A),
                )
            } else {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Color(0xFF00131A), modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = if (status == "available") Aurora.Cyan else Aurora.TextDim, fontSize = 12.sp)
        }
        if (!busy) Icon(Icons.Rounded.ChevronRight, null, tint = Aurora.TextDim, modifier = Modifier.size(22.dp))
    }
    Spacer(Modifier.height(12.dp))
}

/** 「支持的协议」详情页：AirPlay / DLNA 均为自研投送（均支持声画；DLNA 含实时屏幕镜像）。 */
@Composable
fun ProtocolsScreen(nav: NavHostController) {
    val s = L10n.s
    ScreenScaffold(nav, s.protocolsTitle) {
        Column(Modifier.fillMaxWidth().glass(radius = 16).padding(16.dp)) {
            ProtocolLine(s.protoAirplay, s.protoAirplaySub, s.tagVideoAudio, Aurora.Cyan)
            Spacer(Modifier.height(16.dp))
            ProtocolLine(s.protoDlna, s.protoDlnaSub, s.tagVideoAudio, Aurora.Cyan)
        }
    }
}

private const val TERMS_URL = "https://github.com/chunguangwei/AirSonic/blob/main/TERMS.md"
private const val PRIVACY_URL = "https://github.com/chunguangwei/AirSonic/blob/main/PRIVACY.md"
private const val CONTACT_EMAIL = "chunguangwee@gmail.com"

/** 用户协议 + 隐私政策：应用内可离线阅读的摘要 + 跳 GitHub 看完整条款。 */
@Composable
fun LegalScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val s = L10n.s
    ScreenScaffold(nav, s.legalTitle) {
        LegalCard(s.termsHeading, s.termsBody, s.viewFullOnGitHub) { openUrl(ctx, TERMS_URL) }
        Spacer(Modifier.height(12.dp))
        LegalCard(s.privacyHeading, s.privacyBody, s.viewFullOnGitHub) { openUrl(ctx, PRIVACY_URL) }
        Spacer(Modifier.height(12.dp))
        ContactCard(s.contactHeading, s.contactBody, CONTACT_EMAIL) { openEmail(ctx, CONTACT_EMAIL) }
    }
}

@Composable
private fun ContactCard(heading: String, body: String, email: String, onEmail: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(radius = 16).padding(16.dp)) {
        Text(heading, color = Aurora.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Aurora.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            email, color = Aurora.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onEmail() },
        )
    }
}

/** 用系统邮件 App 打开撰写界面（mailto），失败静默（无邮件 App 不崩）。 */
private fun openEmail(ctx: android.content.Context, email: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:$email"))
                .putExtra(Intent.EXTRA_SUBJECT, "AirSonic 反馈 / Bug")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun LegalCard(heading: String, body: String, linkLabel: String, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(radius = 16).padding(16.dp)) {
        Text(heading, color = Aurora.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Aurora.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            linkLabel, color = Aurora.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onOpen() },
        )
    }
}

/** 用系统浏览器打开 URL；失败静默（无网/无浏览器不崩）。 */
private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun ProtocolLine(name: String, sub: String, tag: String, tagColor: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Aurora.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Aurora.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .background(tagColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) { Text(tag, color = tagColor, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = Aurora.TextDim, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingRow(icon: ImageVector?, title: String, sub: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().glass(radius = 16)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(38.dp).background(Aurora.brandBrush, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color(0xFF00131A), modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Aurora.TextDim, fontSize = 12.sp)
        }
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = Aurora.TextDim, modifier = Modifier.size(22.dp))
    }
    Spacer(Modifier.height(12.dp))
}