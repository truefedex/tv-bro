package com.phlox.tvwebbrowser.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.widget.Toast

/**
 * Shared launcher for the alternative (non built-in) players, so both web engines hand a stream
 * over exactly the same way. The built-in ExoPlayer is launched through [NativeVideoActivity]
 * directly; the platform MediaPlayer fallback lives in [SystemPlayerActivity] and external players
 * (VLC, MX Player...) are resolved at runtime.
 */
object PlayerLauncher {

    /** Opens the stream in [SystemPlayerActivity] (platform MediaPlayer fallback). */
    fun openSystem(context: Context, url: String, userAgent: String?, referer: String?, cookie: String?) {
        PlaybackFailure.last = null
        val intent = Intent(context, SystemPlayerActivity::class.java).apply {
            putExtra(SystemPlayerActivity.EXTRA_URL, url)
            userAgent?.let { putExtra(SystemPlayerActivity.EXTRA_UA, it) }
            referer?.let { putExtra(SystemPlayerActivity.EXTRA_REFERER, it) }
            cookie?.let { putExtra(SystemPlayerActivity.EXTRA_COOKIE, it) }
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.player_none_installed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Hands the stream over to an external player. Lists the players actually installed on the
     * device and lets the user pick one, so the absence of any player can be reported instead of
     * silently doing nothing. [onStarted] fires once a player was actually launched (used by the
     * WebView engine to offer the built-in player if the external app bounces straight back).
     */
    fun openExternal(activity: Activity, url: String, userAgent: String?, referer: String?,
                     cookie: String?, onStarted: (() -> Unit)? = null) {
        val intent = buildExternalIntent(url, userAgent, referer, cookie)
        val pm = activity.packageManager

        //Some players only declare video/*, others the concrete HLS mime type,
        //so probe with both and merge the results per package.
        val players = LinkedHashMap<String, ResolveInfo>()
        for (probe in listOf(intent, buildExternalIntent(url, userAgent, referer, cookie, "video/*"))) {
            for (info in pm.queryIntentActivities(probe, 0)) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == activity.packageName) continue
                players.getOrPut(pkg) { info }
            }
        }
        val list = players.values.toList()
        when {
            list.isEmpty() ->
                Toast.makeText(activity, R.string.player_none_installed, Toast.LENGTH_LONG).show()
            list.size == 1 -> startExternalPlayer(activity, intent, list[0], onStarted)
            else -> {
                val labels = list.map { it.loadLabel(pm).toString() }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle(R.string.player_choose_external)
                    .setItems(labels) { _, which -> startExternalPlayer(activity, intent, list[which], onStarted) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun startExternalPlayer(activity: Activity, intent: Intent, player: ResolveInfo,
                                    onStarted: (() -> Unit)?) {
        val explicit = Intent(intent).apply {
            setClassName(player.activityInfo.packageName, player.activityInfo.name)
        }
        try {
            activity.startActivity(explicit)
            onStarted?.invoke()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.player_none_installed, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildExternalIntent(url: String, userAgent: String?, referer: String?, cookie: String?,
                                    forceMime: String? = null): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), forceMime ?: guessMediaMime(url))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val headers = ArrayList<String>()
            if (userAgent != null) { headers.add("User-Agent"); headers.add(userAgent) }
            if (referer != null) { headers.add("Referer"); headers.add(referer) }
            if (cookie != null) { headers.add("Cookie"); headers.add(cookie) }
            if (headers.isNotEmpty()) putExtra("headers", headers.toTypedArray())
            if (userAgent != null) putExtra("User-Agent", userAgent)
            if (referer != null) putExtra("Referer", referer)
            putExtra("secure_uri", true)
        }
    }

    private fun guessMediaMime(u: String): String {
        val p = u.substringBefore('?').lowercase()
        return when {
            p.endsWith(".m3u8") -> "application/x-mpegURL"
            p.endsWith(".txt") -> "application/x-mpegURL" //HLS manifest behind a .txt name
            p.endsWith(".mpd") -> "application/dash+xml"
            p.endsWith(".mp4") || p.endsWith(".m4v") -> "video/mp4"
            p.endsWith(".webm") -> "video/webm"
            else -> "video/*"
        }
    }
}
