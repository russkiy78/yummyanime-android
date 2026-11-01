package com.yani.client.download

import android.app.Notification
import android.app.PendingIntent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.yani.client.MainActivity
import com.yani.client.R
import com.yani.client.YummyApplication

/** Keeps downloads running in the background, with a progress notification that opens the downloads screen. */
@OptIn(UnstableApi::class)
class YummyDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads,
    0,
) {
    private val notifications by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as YummyApplication).container.downloads.manager

    // Downloads that wait for a network keep the service in the foreground; no restart scheduling is needed.
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            MainActivity.downloadsIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return notifications.buildProgressNotification(this, R.drawable.ic_stat_download, open, null, downloads, notMetRequirements)
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "downloads"
    }
}
