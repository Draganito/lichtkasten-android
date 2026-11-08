package ch.bojovic.lichtkasten

import android.app.Application
import android.os.StrictMode
import ch.bojovic.lichtkasten.core.initLogging
import ch.bojovic.lichtkasten.media.RustFetcher
import ch.bojovic.lichtkasten.media.RustKeyer
import ch.bojovic.lichtkasten.media.RustThumbCache
import ch.bojovic.lichtkasten.media.ThumbFetcher
import ch.bojovic.lichtkasten.media.ThumbKeyer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class LichtkastenApp : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Disk on the main thread is logged (a jank bug to fix); network
            // on any thread is impossible by manifest, and fatal if it ever
            // shows up anyway.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyDeathOnNetwork()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build(),
            )
        }
        initLogging()
        appScope.launch { RustThumbCache.trim(this@LichtkastenApp) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ThumbKeyer())
                add(ThumbFetcher.Factory(this@LichtkastenApp))
                add(RustKeyer())
                add(RustFetcher.Factory(this@LichtkastenApp))
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.20).build()
            }
            .diskCache {
                // Coil's own cache holds video frames and nothing else of
                // note: images come straight from MediaStore, Rust results
                // have their own JPEG cache.
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
}
