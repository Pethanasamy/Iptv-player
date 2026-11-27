package com.mari.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.m3u"
const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

data class Channel(val name: String, val logo: String?, val group: String, val url: String)

private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

fun parseM3u(text: String): List<Channel> {
    val out = ArrayList<Channel>(12000)
    var name = ""; var logo: String? = null; var group = "Other"
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF") -> {
                val attrs = ATTR.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                group = attrs["group-title"]?.takeIf { it.isNotBlank() }?.substringBefore(';') ?: "Other"
                name = line.substringAfterLast(',').trim()
            }
            line.isNotEmpty() && !line.startsWith("#") -> {
                if (name.isNotEmpty()) out.add(Channel(name, logo, group, line))
                name = ""
            }
        }
    }
    return out
}

suspend fun loadChannels(cache: File): List<Channel> = withContext(Dispatchers.IO) {
    val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS
    if (!fresh) {
        try {
            val c = URL(PLAYLIST_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            val tmp = File(cache.path + ".tmp")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(cache)
        } catch (_: Exception) { /* fall back to old cache if any */ }
    }
    if (cache.exists()) parseM3u(cache.readText()) else emptyList()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("All") }
    var current by remember { mutableStateOf<Channel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        channels = loadChannels(File(ctx.cacheDir, "playlist.m3u"))
        loading = false
    }

    // Low-latency live buffering + HLS-friendly HTTP settings
    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000).setReadTimeoutMs(10000)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) IPTVPlayer/1.0")
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000).build()
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(lc).build()
    }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "Intha channel ippo work aagala. Vera try pannunga." }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) error = null }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    val groups = remember(channels) { listOf("All") + channels.map { it.group }.distinct().sorted() }
    val filtered = remember(channels, query, group) {
        channels.filter { (group == "All" || it.group == group) && (query.isBlank() || it.name.contains(query, true)) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (current != null) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                error?.let { Text(it, Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            Text(current!!.name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search channels") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it }) { g ->
                FilterChip(selected = g == group, onClick = { group = g }, label = { Text(g) })
            }
        }
        if (loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.url }) { ch ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        current = ch; error = null
                        player.setMediaItem(MediaItem.fromUri(ch.url)); player.prepare(); player.playWhenReady = true
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(ch.logo, null, Modifier.size(48.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Fit)
                    Spacer(Modifier.width(12.dp))
                    Column { Text(ch.name, maxLines = 1); Text(ch.group, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
