package com.mikeos.video.net

import android.content.Context
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared Coil [ImageLoader] for video thumbnails (mikevideo-cloud `/media/{user}/{id}/thumb.jpg`).
 *
 * Media on the cloud is gated on the unguessable video_id (a v4 uuid), NOT on X-API-KEY (see
 * mikevideo-cloud serve_media), so no auth header is needed — just DoH for this ROM's flaky
 * system DNS. Coil downsamples each thumb to the composable's size, so a thumbnail is never
 * decoded at full resolution into RAM (the house memory rule).
 */
object VideoImages {
    @Volatile private var instance: ImageLoader? = null

    fun loader(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient {
                    OkHttpClient.Builder()
                        .dns(Doh.dns)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(25, TimeUnit.SECONDS)
                        .build()
                }
                .crossfade(true)
                .build()
                .also { instance = it }
        }
}
