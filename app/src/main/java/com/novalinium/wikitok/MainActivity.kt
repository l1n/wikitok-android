package com.novalinium.wikitok

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    val article = feed[page]
                    ArticleCard(
                        article = article,
                        isSaved = vm.isSaved(article, saved),
                        onToggleSaved = { vm.toggleSaved(article) },
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

@Composable
fun ArticleCard(article: Article, isSaved: Boolean, onToggleSaved: () -> Unit) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101318))) {
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
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.url}")
                }
                context.startActivity(Intent.createChooser(send, "Share article"))
            }) {
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)))
            }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in browser", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        // Bottom text block
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, end = 72.dp, bottom = 24.dp),
        ) {
            Text(
                article.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (article.extract.isNotBlank()) {
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
