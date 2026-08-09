package com.novalinium.wikitok

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("debug_titles")?.let {
            WikiTokViewModel.debugTitles = it.split('|').filter(String::isNotBlank)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Twice-daily article digest; KEEP means re-launching never resets the cadence.
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyDigestWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<DailyDigestWorker>(
                12, java.util.concurrent.TimeUnit.HOURS
            ).setInitialDelay(12, java.util.concurrent.TimeUnit.HOURS).setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            ).build(),
        )
        if (intent.getBooleanExtra("notify_now", false)) {
            androidx.work.WorkManager.getInstance(this).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<DailyDigestWorker>().build()
            )
        }
        // Wikimedia rejects requests without an identifying User-Agent (HTTP 403)
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(this)
                .okHttpClient(
                    okhttp3.OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header("User-Agent", "WikiTok-Android/1.0 (personal project)")
                                    .build()
                            )
                        }
                        .build()
                )
                .crossfade(true)
                .build()
        )
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    WikiTokApp()
                }
            }
        }
    }
}

@Composable
fun WikiTokApp(vm: WikiTokViewModel = viewModel()) {
    var showSaved by remember { mutableStateOf(false) }
    if (showSaved) {
        BackHandler { showSaved = false }
        SavedScreen(vm, onBack = { showSaved = false })
    } else {
        FeedScreen(vm, onOpenSaved = { showSaved = true })
    }
}

@Composable
fun FeedScreen(vm: WikiTokViewModel, onOpenSaved: () -> Unit) {
    val feed by vm.feed.collectAsState()
    val saved by vm.saved.collectAsState()
    val language by vm.language.collectAsState()
    val error by vm.error.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            feed.isEmpty() && error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't reach Wikipedia", color = Color.White)
                    Text(error ?: "", color = Color.Gray, fontSize = 12.sp)
                    TextButton(onClick = { vm.loadMore() }) { Text("Retry") }
                }
            }
            feed.isEmpty() -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
            else -> {
                val pagerState = rememberPagerState(pageCount = { feed.size })
                LaunchedEffect(pagerState.currentPage, feed.size) {
                    vm.ensureLoaded(pagerState.currentPage)
                }
                // Dwell tracking: keyed on the displayed article (not the index) so
                // feed appends/inserts don't split or misattribute a dwell.
                val currentArticle = feed.getOrNull(pagerState.currentPage)
                LaunchedEffect(currentArticle?.pageid, currentArticle?.lang) {
                    val article = currentArticle ?: return@LaunchedEffect
                    val start = android.os.SystemClock.elapsedRealtime()
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        val seconds =
                            (android.os.SystemClock.elapsedRealtime() - start) / 1000f
                        vm.onDwell(article, seconds)
                    }
                }
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    key = { feed[it].let { a -> "${a.lang}-${a.pageid}" } },
                ) { page ->
                    val article = feed[page]
                    ArticleCard(
                        article = article,
                        isSaved = vm.isSaved(article, saved),
                        isActive = pagerState.currentPage == page,
                        onToggleSaved = { vm.toggleSaved(article) },
                        onExpanded = { vm.onExtractExpanded(article) },
                    )
                }
            }
        }

        // Top bar overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "WikiTok",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(modifier = Modifier.weight(1f))
            LanguagePicker(current = language, onSelect = { vm.setLanguage(it) })
            IconButton(onClick = onOpenSaved) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "Saved articles", tint = Color.White)
            }
        }
    }
}

@Composable
fun LanguagePicker(current: Language, onSelect: (Language) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Language,
                contentDescription = "Language",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(
                " ${current.code.uppercase()}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = { Text("${lang.label} (${lang.code})") },
                    onClick = {
                        expanded = false
                        onSelect(lang)
                    },
                )
            }
        }
    }
}

// Deterministic gradient art for articles without a lead image.
private val PLACEHOLDER_GRADIENTS = listOf(
    listOf(Color(0xFF355C7D), Color(0xFF6C5B7B), Color(0xFFC06C84)),
    listOf(Color(0xFF2C3E50), Color(0xFF4CA1AF)),
    listOf(Color(0xFF134E5E), Color(0xFF71B280)),
    listOf(Color(0xFF41295A), Color(0xFF2F0743)),
    listOf(Color(0xFF1E3C72), Color(0xFF2A5298)),
    listOf(Color(0xFF614385), Color(0xFF516395)),
    listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B)),
    listOf(Color(0xFF232526), Color(0xFF414345)),
)

@Composable
fun ArticleCard(
    article: Article,
    isSaved: Boolean,
    onToggleSaved: () -> Unit,
    isActive: Boolean = false,
    onExpanded: () -> Unit = {},
) {
    val context = LocalContext.current
    var expanded by remember(article.pageid) { mutableStateOf(false) }
    var burst by remember { mutableStateOf(0) }
    val burstScale = remember { androidx.compose.animation.core.Animatable(0f) }
    val burstAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(burst) {
        if (burst > 0) {
            burstAlpha.snapTo(1f)
            burstScale.snapTo(0.4f)
            burstScale.animateTo(
                1.1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 900f),
            )
            burstAlpha.animateTo(0f, androidx.compose.animation.core.tween(350))
        }
    }
    val share = {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.url}")
        }
        context.startActivity(Intent.createChooser(send, "Share article"))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101318))
            .pointerInput(article.pageid) {
                detectTapGestures(
                    onTap = {
                        expanded = !expanded
                        if (expanded) onExpanded()
                    },
                    onDoubleTap = {
                        if (!isSaved) onToggleSaved()
                        burst++
                    },
                    onLongPress = { share() },
                )
            },
    ) {
        if (article.thumbnail != null) {
            AsyncImage(
                model = article.thumbnail.source,
                contentDescription = article.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is coil.compose.AsyncImagePainter.State.Error) {
                        android.util.Log.e(
                            "WikiTok",
                            "image failed: ${article.thumbnail.source}",
                            state.result.throwable,
                        )
                    }
                },
            )
        } else {
            val palette = PLACEHOLDER_GRADIENTS[
                kotlin.math.abs(article.title.hashCode()) % PLACEHOLDER_GRADIENTS.size
            ]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(palette)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    article.title.firstOrNull()?.uppercase() ?: "W",
                    color = Color.White.copy(alpha = 0.14f),
                    fontSize = 280.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        // Videos take over the card the moment it becomes the current page.
        if (article.videoUrl != null && isActive) {
            val exoPlayer = remember(article.videoUrl) {
                // Wikimedia 429s HttpURLConnection's client fingerprint regardless of
                // UA; OkHttp (which Coil also uses) gets through fine.
                val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                    okhttp3.OkHttpClient()
                ).setUserAgent("WikiTok-Android/1.0 (personal project)")
                androidx.media3.exoplayer.ExoPlayer.Builder(context)
                    .setMediaSourceFactory(
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                            androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
                        )
                    )
                    .build().apply {
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() {
                            android.util.Log.d("WikiTok", "video first frame: ${article.title}")
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            android.util.Log.d("WikiTok", "video state=$state: ${article.title}")
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.e("WikiTok", "video error: ${article.title}", error)
                        }
                    })
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(article.videoUrl))
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = true
                    prepare()
                }
            }
            androidx.compose.runtime.DisposableEffect(article.videoUrl) {
                onDispose { exoPlayer.release() }
            }
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Scrim so text is readable over any image
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.4f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    )
                )
        )

        // Right action rail
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = 8.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onToggleSaved) {
                Icon(
                    if (isSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isSaved) "Unsave" else "Save",
                    tint = if (isSaved) Color(0xFFFF3B5C) else Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = { share() }) {
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)))
            }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in browser", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        // Bottom text block — tap toggles between preview and full extract
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, end = 72.dp, bottom = 24.dp)
                .animateContentSize(),
        ) {
            if (article.isHighlight) {
                Text(
                    "★ Today's featured article",
                    color = Color(0xFFFFD75E),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                article.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (article.extract.isNotBlank()) {
                if (expanded) {
                    Text(
                        article.extract,
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                } else {
                    Text(
                        article.extract,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 7,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Double-tap heart burst
        if (burstAlpha.value > 0f) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color(0xFFFF3B5C),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = burstScale.value
                        scaleY = burstScale.value
                        alpha = burstAlpha.value
                    },
            )
        }
    }
}

@Composable
fun SavedScreen(vm: WikiTokViewModel, onBack: () -> Unit) {
    val saved by vm.saved.collectAsState()
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101318))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "Saved articles",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (saved.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.BookmarkBorder,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp),
                    )
                    Text("Tap the heart on an article to save it", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(saved, key = { "${it.lang}-${it.pageid}" }) { article ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)))
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (article.thumbnail != null) {
                            AsyncImage(
                                model = article.thumbnail.source,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Color.Gray)
                            }
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                article.title,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (article.extract.isNotBlank()) {
                                Text(
                                    article.extract,
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = { vm.toggleSaved(article) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}
