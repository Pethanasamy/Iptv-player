package com.mari.iptv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val LANG_URL = "https://iptv-org.github.io/iptv/index.language.m3u"
const val CAT_URL = "https://iptv-org.github.io/iptv/index.category.m3u"
const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L
const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

data class Raw(val name: String, val logo: String?, val group: String, val url: String, val referer: String?, val ua: String?)
data class Channel(
    val name: String, val logo: String?, val category: String,
    val languages: List<String>, val url: String, val referer: String?, val ua: String?
)

private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

fun parseM3u(file: File): List<Raw> {
    val out = ArrayList<Raw>(12000)
    var name = ""
    var logo: String? = null
    var group = "Undefined"
    var ref: String? = null
    var ua: String? = null
    file.useLines { lines ->
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val attrs = ATTR.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                group = attrs["group-title"]?.takeIf { it.isNotBlank() }?.substringBefore(';')?.trim() ?: "Undefined"
                val lastQuote = line.lastIndexOf('"')
                val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                ref = null
                ua = null
            } else if (line.startsWith("#EXTVLCOPT:")) {
                val kv = line.removePrefix("#EXTVLCOPT:")
                if (kv.startsWith("http-referrer=") || kv.startsWith("http-referer=")) ref = kv.substringAfter('=')
                else if (kv.startsWith("http-user-agent=")) ua = kv.substringAfter('=')
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                if (name.isNotEmpty()) out.add(Raw(name, logo, group, line, ref, ua))
                name = ""
            }
        }
    }
    return out
}

private fun download(urlStr: String, dest: File) {
    val c = URL(urlStr).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 60000
    val tmp = File(dest.path + ".tmp")
    try {
        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    } finally {
        c.disconnect()
    }
}

private fun ensure(file: File, url: String, force: Boolean) {
    val fresh = !force && file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MAX_AGE_MS
    if (!fresh) {
        try { download(url, file) } catch (_: Exception) { /* keep old cache if any */ }
    }
}

suspend fun loadChannels(dir: File, force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
    try {
        val langF = File(dir, "lang.m3u")
        val catF = File(dir, "cat.m3u")
        coroutineScope {
            val a = async { ensure(langF, LANG_URL, force) }
            val b = async { ensure(catF, CAT_URL, force) }
            a.await(); b.await()
        }
        val langRaw = if (langF.exists()) parseM3u(langF) else emptyList()
        val catRaw = if (catF.exists()) parseM3u(catF) else emptyList()
        val catByUrl = HashMap<String, String>()
        catRaw.forEach { catByUrl.putIfAbsent(it.url, it.group) }
        val langsByUrl = HashMap<String, LinkedHashSet<String>>()
        langRaw.forEach { langsByUrl.getOrPut(it.url) { LinkedHashSet() }.add(it.group) }
        val base = if (langRaw.isNotEmpty()) langRaw else catRaw
        val seen = HashSet<String>()
        val out = ArrayList<Channel>(base.size)
        for (r in base) {
            if (!seen.add(r.url)) continue // duplicate URLs crashed the list before
            out.add(
                Channel(
                    r.name, r.logo, catByUrl[r.url] ?: "Undefined",
                    langsByUrl[r.url]?.toList() ?: listOf("Undefined"),
                    r.url, r.referer, r.ua
                )
            )
        }
        out.sortBy { it.name.lowercase() }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(this) }
            }
        }
    }
}

@Composable
fun PlayerBox(player: ExoPlayer, controller: Boolean, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                useController = controller
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { it.player = player }
    )
}

@Composable
fun ErrorOverlay(msg: String?, modifier: Modifier = Modifier) {
    if (msg != null) {
        Box(modifier.background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Text(msg, color = Color.White, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun ChannelRow(ch: Channel, selected: Boolean, dead: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        selected -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dead) 0.4f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            ch.logo, null,
            Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ch.languages.joinToString(", ") + " · " + ch.category,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun App(activity: ComponentActivity) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("kaatchi", Context.MODE_PRIVATE) }
    val isTv = remember {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(prefs.getString("lang", "All") ?: "All") }
    var cat by remember { mutableStateOf("All") }
    var hideDead by remember { mutableStateOf(true) }
    var dead by remember { mutableStateOf<Set<String>>(prefs.getStringSet("dead", emptySet())?.toSet() ?: emptySet()) }
    var current by remember { mutableStateOf<Channel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tvFull by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }
    val currentRef = rememberUpdatedState(current)
    val listState = rememberLazyListState()
    val fullFocus = remember { FocusRequester() }

    LaunchedEffect(reload) {
        loading = true
        channels = loadChannels(ctx.filesDir, force = reload > 0)
        loading = false
    }
    LaunchedEffect(lang) { prefs.edit().putString("lang", lang).apply() }
    LaunchedEffect(dead) { prefs.edit().putStringSet("dead", HashSet(dead)).apply() }
    LaunchedEffect(lang, cat, query, hideDead) { runCatching { listState.scrollToItem(0) } }

    // Player (low-latency live buffering, per-channel headers)
    val http = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
            .setUserAgent(DEFAULT_UA)
    }
    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 1_500, 3_000).build())
            .build()
    }

    fun play(ch: Channel) {
        current = ch
        error = null
        try {
            http.setUserAgent(ch.ua ?: DEFAULT_UA)
            http.setDefaultRequestProperties(if (ch.referer != null) mapOf("Referer" to ch.referer) else emptyMap())
            player.setMediaItem(MediaItem.fromUri(ch.url))
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            error = "Intha channel open aagala."
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                val netIssue = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                error = if (netIssue) "Network / timeout. Marubadi try pannunga." else "Intha channel ippo work aagala (source dead)."
                if (!netIssue) currentRef.value?.let { c -> dead = dead + c.url }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    error = null
                    currentRef.value?.let { c -> if (c.url in dead) dead = dead - c.url }
                }
            }
        }
        val observer = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) player.pause()
            else if (ev == Lifecycle.Event.ON_START && currentRef.value != null) player.play()
        }
        player.addListener(listener)
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    // Filters
    val langCounts = remember(channels) {
        HashMap<String, Int>().also { m ->
            channels.forEach { c -> c.languages.forEach { l -> m[l] = (m[l] ?: 0) + 1 } }
        }
    }
    val languages = remember(langCounts) {
        val pinned = listOf("Tamil", "English", "Hindi", "Telugu", "Malayalam", "Kannada").filter { it in langCounts }
        val rest = langCounts.keys.filter { it !in pinned && it != "Undefined" }.sortedByDescending { langCounts[it] ?: 0 }
        listOf("All") + pinned + rest + (if ("Undefined" in langCounts) listOf("Undefined") else emptyList())
    }
    val categories = remember(channels) { listOf("All") + channels.map { it.category }.distinct().sorted() }
    val filtered = remember(channels, query, lang, cat, hideDead, dead) {
        channels.filter {
            (lang == "All" || lang in it.languages) &&
                    (cat == "All" || it.category == cat) &&
                    (query.isBlank() || it.name.contains(query, true)) &&
                    !(hideDead && it.url in dead)
        }
    }

    fun zap(d: Int) {
        if (filtered.isEmpty()) return
        val i = filtered.indexOfFirst { it.url == current?.url }
        val n = if (i < 0) 0 else (i + d + filtered.size) % filtered.size
        play(filtered[n])
    }

    // Fullscreen handling
    val phoneFull = !isTv && landscape && current != null
    val fullscreen = phoneFull || tvFull
    LaunchedEffect(fullscreen) {
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(tvFull) {
        if (tvFull) {
            delay(80)
            runCatching { fullFocus.requestFocus() }
        }
    }
    LaunchedEffect(current) {
        showName = true
        delay(3000)
        showName = false
    }

    // Back: TV fullscreen -> exit fullscreen. Otherwise press twice to exit app.
    val lastBack = remember { longArrayOf(0L) }
    BackHandler {
        if (tvFull) {
            tvFull = false
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBack[0] < 2000) {
                activity.finish()
            } else {
                lastBack[0] = now
                Toast.makeText(ctx, "Exit panna back-a marubadiyum amukkunga", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val browser: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search channels") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(languages, key = { it }) { g ->
                    FilterChip(
                        selected = g == lang, onClick = { lang = g },
                        label = { Text(if (g == "All") "All languages" else "$g · ${langCounts[g] ?: 0}") }
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                item(key = "__dead") {
                    FilterChip(
                        selected = hideDead, onClick = { hideDead = !hideDead },
                        label = { Text("Hide dead (${dead.size})") }
                    )
                }
                items(categories, key = { "c_$it" }) { g ->
                    FilterChip(selected = g == cat, onClick = { cat = g }, label = { Text(if (g == "All") "All categories" else g) })
                }
            }
            Text(
                "${filtered.size} channels", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            if (loading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else if (channels.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playlist load aagala. Internet check pannunga.")
                    Button(onClick = { reload++ }, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(filtered, key = { it.url }) { ch ->
                        ChannelRow(ch, selected = ch.url == current?.url, dead = ch.url in dead) {
                            if (isTv && current?.url == ch.url && error == null) tvFull = true else play(ch)
                        }
                    }
                }
            }
        }
    }

    val rootMod = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().systemBarsPadding().imePadding()
    Box(rootMod) {
        if (isTv) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.42f).fillMaxHeight()) { browser(Modifier.fillMaxSize()) }
                Column(Modifier.weight(0.58f).fillMaxHeight().padding(16.dp)) {
                    if (current == null) {
                        Text("Channel select pannunga")
                    } else if (!tvFull) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                            PlayerBox(player, controller = false, modifier = Modifier.fillMaxSize())
                            ErrorOverlay(error, Modifier.fillMaxSize())
                        }
                        Text(current?.name ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        Text(
                            "Same channel-la OK = fullscreen · Fullscreen-la Up/Down = channel maatru",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            if (tvFull) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black)
                        .onKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown) {
                                when (ev.key) {
                                    Key.DirectionUp, Key.ChannelUp -> { zap(-1); true }
                                    Key.DirectionDown, Key.ChannelDown -> { zap(1); true }
                                    Key.DirectionLeft, Key.DirectionRight -> true
                                    else -> false
                                }
                            } else false
                        }
                        .focusRequester(fullFocus)
                        .focusable()
                ) {
                    PlayerBox(player, controller = false, modifier = Modifier.fillMaxSize())
                    ErrorOverlay(error, Modifier.fillMaxSize())
                    if (showName) {
                        Text(
                            current?.name ?: "", color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.TopStart).padding(24.dp)
                        )
                    }
                }
            }
        } else if (phoneFull) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PlayerBox(player, controller = true, modifier = Modifier.fillMaxSize())
                ErrorOverlay(error, Modifier.fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (current != null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                        PlayerBox(player, controller = true, modifier = Modifier.fillMaxSize())
                        ErrorOverlay(error, Modifier.fillMaxSize())
                    }
                    Text(current?.name ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
                }
                browser(Modifier.weight(1f))
            }
        }
    }
}
