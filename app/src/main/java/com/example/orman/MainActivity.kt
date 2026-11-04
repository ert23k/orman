package com.example.orman

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // Uygulama tamamen kapanmış olsa bile yarım kalan oturumu devam ettir
        if (Store.aStart != 0L && !Session.active) {
            try {
                startForegroundService(Intent(this, FocusService::class.java).setAction("RESUME"))
            } catch (e: Exception) { }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1F5A3D))) { App() }
        }
    }

    override fun onResume() {
        super.onResume()
        Store.usageGranted = hasUsageAccess(this)
    }
}

private val BG = Color(0xFFD5E6D6)
private val RED = Color(0xFFA5432F)

fun fmtT(s: Long): String {
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
fun fm(m: Int): String = if (m >= 60) "${m / 60} sa ${m % 60} dk" else "$m dk"
fun dayOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
fun minOn(d: LocalDate): Int = Store.trees.filter { it.ok && dayOf(it.t) == d }.sumOf { it.min }

@Composable
fun Chip(text: String, sel: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50))
            .background(if (sel) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = if (sel) cs.onPrimary else cs.onSurface, fontSize = 14.sp) }
}

@Composable
fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) { content() }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("🌱" to "Odak", "🌳" to "Orman", "📊" to "İstatistik", "🛒" to "Mağaza", "⚙️" to "Ayarlar")
                    .forEachIndexed { i, (e, l) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Text(e, fontSize = 20.sp) }, label = { Text(l, fontSize = 10.sp) }
                        )
                    }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> FocusScreen()
                1 -> ForestScreen()
                2 -> StatsScreen()
                3 -> ShopScreen()
                else -> SettingsScreen()
            }
        }
    }
    Session.result?.let { r ->
        AlertDialog(
            onDismissRequest = { Session.result = null },
            confirmButton = { TextButton(onClick = { Session.result = null }) { Text("Tamam") } },
            title = { Text(if (r.ok) "Ağacın büyüdü!" else "Ağacın soldu") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(140.dp).clip(CircleShape).background(BG)) {
                        TreeView(r.sp, 1f, !r.ok, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (r.ok) "${r.min} dakika odaklandın, ${r.min} altın kazandın." else r.reason)
                }
            }
        )
    }
}

@Composable
fun GoalPanel() {
    val today = minOn(LocalDate.now())
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Günlük hedef", fontWeight = FontWeight.SemiBold)
            Text("${fm(today)} / ${fm(Store.goal)}")
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { (today.toFloat() / Store.goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Slider(
            value = Store.goal.toFloat(),
            onValueChange = { Store.goal = ((it / 15f).roundToInt() * 15).coerceIn(15, 360) },
            onValueChangeFinished = { Store.save() },
            valueRange = 15f..360f
        )
        Text("15 dakika ile 6 saat arasında ayarlanır.", fontSize = 12.sp)
    }
}

@Composable
fun FocusScreen() {
    val ctx = LocalContext.current
    var confirm by remember(Session.active) { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Orman", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("🪙 ${Store.coins}", fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(220.dp).clip(CircleShape).background(BG)) {
            if (Session.active) TreeView(Session.sp, Session.progress, false, Modifier.fillMaxSize())
            else TreeView(Store.sel, 0.6f, false, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))
        if (Session.active) {
            Text(fmtT(Session.remaining), fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
            Text("${Session.tag} · ${if (Session.mode == "down") "Odak zamanı" else "Kronometre"}")
            if (Store.aStrict && Session.away > 0)
                Text("İzinli olmayan uygulamadasın: ${10 - Session.away} sn içinde dön!", color = RED)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                if (Session.mode == "up" || confirm) {
                    ctx.startService(Intent(ctx, FocusService::class.java).setAction("STOP"))
                    confirm = false
                } else confirm = true
            }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (Session.mode == "up") "Bitir ve ağacı dik"
                    else if (confirm) "Emin misin? Ağaç ölecek – tekrar dokun" else "Vazgeç"
                )
            }
            Text("Uygulamayı kapatsan bile oturum arka planda devam eder.", fontSize = 12.sp)
        } else {
            Panel {
                Row {
                    Chip("Zamanlayıcı", Store.mode == "down") { Store.mode = "down"; Store.save() }
                    Chip("Kronometre", Store.mode == "up") { Store.mode = "up"; Store.save() }
                }
                if (Store.mode == "down") {
                    Text("Süre: ${Store.dur} dk", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = Store.dur.toFloat(),
                        onValueChange = { Store.dur = it.roundToInt().coerceIn(1, 180) },
                        onValueChangeFinished = { Store.save() },
                        valueRange = 1f..180f
                    )
                    Chips { listOf(15, 25, 45, 60, 90).forEach { m -> Chip("$m dk", Store.dur == m) { Store.dur = m; Store.save() } } }
                } else {
                    Text("Süre sınırı yok. Bitir’e bastığında en az 10 dakika geçtiyse ağaç dikilir; ağaç 60 dakikada tam boyuna ulaşır.")
                }
            }
            Panel {
                Text("Etiket", fontWeight = FontWeight.SemiBold)
                Chips { Store.tags.forEach { t -> Chip(t, Store.tag == t) { Store.tag = t; Store.save() } } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag, onValueChange = { if (it.length <= 14) newTag = it },
                        singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Yeni etiket") }
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val n = newTag.trim()
                        if (n.isNotEmpty() && n !in Store.tags && Store.tags.size < 12) {
                            Store.tags = Store.tags + n; Store.tag = n; Store.save()
                        }
                        newTag = ""
                    }) { Text("Ekle") }
                }
            }
            Panel {
                Text("Ortam sesi", fontWeight = FontWeight.SemiBold)
                Chips {
                    listOf("off" to "Kapalı", "rain" to "Yağmur", "wave" to "Dalga", "wind" to "Rüzgâr")
                        .forEach { (k, l) -> Chip(l, Store.sound == k) { Store.sound = k; Store.save() } }
                }
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Katı mod", fontWeight = FontWeight.SemiBold)
                        Text("İzinli listende olmayan bir uygulamada 10 sn’den fazla kalırsan ağaç ölür. İzinli uygulamaları Ayarlar’dan seç.", fontSize = 13.sp)
                    }
                    Switch(checked = Store.strict, onCheckedChange = { Store.strict = it; Store.save() })
                }
            }
            val blocked = Store.strict && !Store.usageGranted
            if (blocked) {
                Text("Katı mod için “Kullanım erişimi” izni gerekli. İzin vermeden başlatılamaz.", color = RED)
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("İzin ayarını aç") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    ctx.startForegroundService(
                        Intent(ctx, FocusService::class.java).putExtra("min", Store.dur).putExtra("mode", Store.mode)
                    )
                },
                enabled = !blocked,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Ağaç dik", fontSize = 18.sp) }
        }
        GoalPanel()
    }
}

@Composable
fun ForestScreen() {
    var period by remember { mutableIntStateOf(0) }
    var page by remember(period) { mutableIntStateOf(Int.MAX_VALUE) }
    val today = LocalDate.now()
    val list = Store.trees.filter {
        val d = dayOf(it.t)
        when (period) {
            0 -> d == today
            1 -> !d.isBefore(today.minusDays(6))
            else -> !d.isBefore(today.minusDays(29))
        }
    }
    val per = 20
    val pages = maxOf(1, (list.size + per - 1) / per)
    val pg = page.coerceIn(0, pages - 1)
    val slice = list.drop(pg * per).take(per)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Ormanın", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Row(Modifier.padding(vertical = 6.dp)) {
            listOf("Bugün", "7 gün", "30 gün").forEachIndexed { i, l -> Chip(l, period == i) { period = i } }
        }
        val ok = list.count { it.ok }
        Text("$ok ağaç" + if (list.size - ok > 0) " · ${list.size - ok} solmuş" else "")
        Spacer(Modifier.height(8.dp))
        for (r in 0 until 5) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 4) {
                    val i = r * 4 + c
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(3.dp)
                            .clip(RoundedCornerShape(12.dp)).background(BG)
                    ) {
                        if (i < slice.size) TreeView(slice[i].sp, 1f, !slice[i].ok, Modifier.fillMaxSize())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page = pg - 1 }, enabled = pg > 0) { Text("‹ Önceki") }
            Text("Sayfa ${pg + 1} / $pages")
            TextButton(onClick = { page = pg + 1 }, enabled = pg < pages - 1) { Text("Sonraki ›") }
        }
        Text("Her sayfa $per ağaç alır.", fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun StatsScreen() {
    val today = LocalDate.now()
    val names = arrayOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
    val okT = Store.trees.filter { it.ok }
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val mins = days.map { minOn(it) }
    val mx = maxOf(1, mins.maxOrNull() ?: 1)
    var streak = 0
    var c = today
    if (minOn(c) == 0) c = c.minusDays(1)
    while (minOn(c) > 0) { streak++; c = c.minusDays(1) }
    val tg = okT.groupBy { if (it.tag.isBlank()) "Etiketsiz" else it.tag }
        .mapValues { e -> e.value.sumOf { it.min } }.toList().sortedByDescending { it.second }
    val tmax = maxOf(1, tg.firstOrNull()?.second ?: 1)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("İstatistik", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Panel { Text("Bugün"); Text(fm(minOn(today)), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
                Panel { Text("Dikilen ağaç"); Text("${okT.size}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Panel { Text("Toplam odak"); Text(fm(okT.sumOf { it.min }), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
                Panel { Text("Seri (gün)"); Text("$streak", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
        Panel {
            Text("Son 7 gün", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().height(130.dp), verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { i, d ->
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().height((90f * mins[i] / mx).coerceAtLeast(3f).dp)
                                .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary)
                        )
                        Text(names[d.dayOfWeek.value - 1], fontSize = 11.sp)
                    }
                }
            }
        }
        Panel {
            Text("Etiketlere göre", fontWeight = FontWeight.SemiBold)
            if (tg.isEmpty()) Text("Henüz veri yok.")
            tg.forEach { (t, m) ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t); Text(fm(m))
                }
                LinearProgressIndicator(progress = { m.toFloat() / tmax }, modifier = Modifier.fillMaxWidth())
            }
        }
        val dead = Store.trees.size - okT.size
        if (dead > 0) Text("Solan ağaç sayısı: $dead")
    }
}

@Composable
fun ShopScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Mağaza", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("🪙 ${Store.coins}  ·  Her odak dakikası 1 altın")
        Spacer(Modifier.height(12.dp))
        SPECIES.forEach { s ->
            val own = s.id in Store.unlocked
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(BG)) { TreeView(s.id, 1f, false, Modifier.fillMaxSize()) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold)
                    Text(if (own) "Sahipsin" else "🪙 ${s.price}")
                }
                when {
                    Store.sel == s.id -> Text("Seçili")
                    own -> Button(onClick = { Store.sel = s.id; Store.save() }) { Text("Seç") }
                    else -> Button(
                        enabled = Store.coins >= s.price,
                        onClick = {
                            Store.coins -= s.price
                            Store.unlocked = Store.unlocked + s.id
                            Store.sel = s.id
                            Store.save()
                        }
                    ) { Text("Satın al") }
                }
            }
        }
        Panel {
            Text("Premium özellikler (hepsi açık)", fontWeight = FontWeight.SemiBold)
            Text("• 5 ağaç türü ve altın mağazası\n• Kronometre modu\n• Katı mod ve otomatik uygulama listesi\n• Ortam sesleri\n• Etiketler ve istatistikler\n• Günlük hedef ve seri\n• Uygulama kapansa da devam eden oturum", fontSize = 13.sp)
        }
    }
}

data class AppInfo(val pkg: String, val label: String)

fun loadApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val l = pm.queryIntentActivities(i, 0)
    return l.map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != ctx.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val apps = remember { loadApps(ctx) }
    var resetAsk by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("Ayarlar", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (Store.usageGranted) "Kullanım erişimi: verildi ✓" else "Kullanım erişimi: verilmedi (katı mod için gerekli)",
                        Modifier.weight(1f)
                    )
                    if (!Store.usageGranted)
                        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("İzin ver") }
                }
            }
            Panel {
                Text("Arka planda kesintisiz çalışma", fontWeight = FontWeight.SemiBold)
                Text("Bazı telefonlar pil tasarrufu için uygulamayı kapatabilir. Orman’ı “Kısıtlama yok / Optimize etme” listesine al.", fontSize = 13.sp)
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Pil ayarlarını aç") }
            }
            Panel {
                Text("Veriler", fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    enabled = !Session.active,
                    onClick = { if (resetAsk) { Store.resetAll(); resetAsk = false } else resetAsk = true }
                ) { Text(if (resetAsk) "Emin misin? Her şey silinecek – tekrar dokun" else "Tüm verileri sıfırla") }
            }
            Text("İzinli uygulamalar (otomatik listelenir)", fontWeight = FontWeight.SemiBold)
            Text("Seçtiklerine girersen ağaç ölmez.", fontSize = 13.sp)
        }
        items(apps, key = { it.pkg }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    Store.allowed = if (a.pkg in Store.allowed) Store.allowed - a.pkg else Store.allowed + a.pkg
                    Store.save()
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = a.pkg in Store.allowed, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text(a.label)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun TreeView(sp: String, p: Float, dead: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 100f
        val bx = size.width / 2f
        val by = size.height * 0.9f
        drawOval(Color(0x556B8F4E), Offset(bx - 30 * u, by - 6 * u), Size(60 * u, 12 * u))
        if (dead) {
            val g = Color(0xFF7A6A5A)
            drawRect(g, Offset(bx - 3 * u, by - 34 * u), Size(6 * u, 34 * u))
            fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(g, Offset(bx + x1 * u, by + y1 * u), Offset(bx + x2 * u, by + y2 * u), 3 * u, StrokeCap.Round)
            ln(0f, -22f, -14f, -34f); ln(0f, -28f, 12f, -42f); ln(0f, -16f, 10f, -24f)
            return@Canvas
        }
        if (p < 0.12f) {
            drawOval(Color(0xFF8B6B4A), Offset(bx - 14 * u, by - 7 * u), Size(28 * u, 14 * u))
            drawLine(Color(0xFF4F9A3A), Offset(bx, by - 4 * u), Offset(bx, by - 20 * u), 3 * u, StrokeCap.Round)
            drawOval(Color(0xFF5FB04A), Offset(bx - 13 * u, by - 24 * u), Size(13 * u, 8 * u))
            drawOval(Color(0xFF5FB04A), Offset(bx, by - 26 * u), Size(13 * u, 8 * u))
            return@Canvas
        }
        val k = u * (0.3f + 0.7f * ((p - 0.12f) / 0.88f).coerceIn(0f, 1f))
        fun rect(x: Float, y: Float, w: Float, h: Float, c: Color, r: Float = 0f) =
            drawRoundRect(c, Offset(bx + x * k, by + y * k), Size(w * k, h * k), CornerRadius(r * k))
        fun dot(x: Float, y: Float, r: Float, c: Color) = drawCircle(c, r * k, Offset(bx + x * k, by + y * k))
        fun tri(y1: Float, w: Float, y2: Float, c: Color) {
            val pa = Path().apply {
                moveTo(bx - w * k, by + y1 * k); lineTo(bx + w * k, by + y1 * k)
                lineTo(bx, by + y2 * k); close()
            }
            drawPath(pa, c)
        }
        val trunk = Color(0xFF6B4A35)
        when (sp) {
            "pine" -> {
                rect(-3f, -20f, 6f, 20f, trunk)
                tri(-14f, 24f, -46f, Color(0xFF2E7D4F))
                tri(-32f, 19f, -62f, Color(0xFF37915C))
                tri(-48f, 13f, -76f, Color(0xFF45A76B))
            }
            "cactus" -> {
                val c = Color(0xFF4F9D5D)
                rect(-7f, -52f, 14f, 52f, c, 7f)
                rect(-22f, -30f, 18f, 8f, c, 4f); rect(-22f, -44f, 8f, 22f, c, 4f)
                rect(4f, -22f, 18f, 8f, c, 4f); rect(14f, -38f, 8f, 24f, c, 4f)
                dot(0f, -54f, 5f, Color(0xFFE8639A))
            }
            else -> {
                val c = when (sp) {
                    "sakura" -> listOf(Color(0xFFF4A7C0), Color(0xFFF8C5D6), Color(0xFFEE8FB0))
                    "maple" -> listOf(Color(0xFFE07A2F), Color(0xFFF0A43A), Color(0xFFC8501F))
                    else -> listOf(Color(0xFF3F9A47), Color(0xFF5FB961), Color(0xFF2F7D3A))
                }
                rect(-4f, -36f, 8f, 36f, trunk)
                dot(-14f, -38f, 14f, c[2]); dot(14f, -38f, 14f, c[2])
                dot(0f, -48f, 20f, c[0]); dot(-6f, -54f, 9f, c[1])
            }
        }
    }
}
