package com.novalinium.wikitok

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Once/twice-daily digest notification: a recommender-ranked pick when a profile
 * exists, otherwise today's featured article, otherwise a random one.
 */
class DailyDigestWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "wikitok_daily"
        const val WORK_NAME = "daily_digest"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("WikiTok", "digest: no notification permission, skipping")
            return Result.success()
        }
        val lang = ctx.wikitokDataStore.data.first()[stringPreferencesKey("language")] ?: "en"
        val personalized = runCatching {
            val ranked = Recommender(ctx).rank(WikipediaApi.fetchRandomBatch(lang))
            ranked.firstOrNull { it.thumbnail != null } ?: ranked.firstOrNull()
        }.getOrNull()
        val article = personalized
            ?: runCatching {
                val today = java.time.LocalDate.now()
                WikipediaApi.fetchDailyFeatured(lang, today.year, today.monthValue, today.dayOfMonth)
            }.getOrNull()
            ?: return Result.retry()
        notify(ctx, article)
        Log.d("WikiTok", "digest notification posted: ${article.title}")
        return Result.success()
    }

    private fun notify(ctx: Context, article: Article) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Daily article", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "A Wikipedia article picked for you" }
        )
        val tap = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra("debug_titles", article.title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(article.title)
            .setContentText(article.extract.take(160))
            .setStyle(NotificationCompat.BigTextStyle().bigText(article.extract.take(500)))
            .setContentIntent(tap)
            .setAutoCancel(true)
        article.thumbnail?.let { thumb ->
            fetchBitmap(thumb.source)?.let { bmp ->
                builder.setLargeIcon(bmp)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bmp)
                            .setSummaryText(article.extract.take(160))
                    )
            }
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun fetchBitmap(url: String): Bitmap? = runCatching {
        OkHttpClient().newCall(
            Request.Builder().url(url)
                .header("User-Agent", "WikiTok-Android/1.0 (personal project)").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.byteStream()?.let(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}
