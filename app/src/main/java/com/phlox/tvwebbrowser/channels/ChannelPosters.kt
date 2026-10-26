package com.phlox.tvwebbrowser.channels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.phlox.tvwebbrowser.R
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * The posters of the home screen channels, drawn by the app (the launcher never loads anything from the
 * network) and served by [ChannelPostersProvider]. A poster file is named by a hash of all that it shows: the
 * launcher keeps images by their uri, so a changed image gets another name. A poster is drawn only when its
 * file is missing, and the files no program uses are deleted after each publication.
 */
object ChannelPosters {
    private const val TAG = "ChannelPosters"
    private const val DIR = "channel_posters"
    /** Raise it when the drawing changes, so every poster is drawn again */
    private const val RENDER_VERSION = 1
    private const val WIDE_WIDTH = 640
    private const val WIDE_HEIGHT = 360
    private const val SQUARE_SIDE = 320

    /** The backgrounds of the drawn tiles, picked by a hash of the site (images, not views: no theme here) */
    private val TILE_COLORS = intArrayOf(
        0xFF3B6EA8.toInt(), 0xFF2E8B7A.toInt(), 0xFF8A5BB0.toInt(), 0xFFB0623B.toInt(),
        0xFF4F7F3A.toInt(), 0xFFA8476A.toInt(), 0xFF3F5F8F.toInt(), 0xFF7A6A3A.toInt()
    )

    /** Kept (not a cache: the launcher may ask for a poster any time) and out of backups (pictures of open pages) */
    fun postersDir(context: Context) = File(context.noBackupFilesDir, DIR)

    private fun kindDir(context: Context, kind: ChannelKind) = File(postersDir(context), kind.id)

    /** The file name part of a poster made of [parts] */
    fun key(vararg parts: Any?): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("v$RENDER_VERSION".toByteArray())
        for (part in parts) {
            digest.update(0)
            digest.update(part.toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.substring(0, 32)
    }

    /**
     * The uri of the poster [key] of the [kind], drawn by [render] when its file is missing.
     * @return null when there is no file and [render] gives nothing
     */
    fun poster(context: Context, kind: ChannelKind, key: String, render: () -> Bitmap?): String? {
        val dir = kindDir(context, kind)
        val file = File(dir, "$key.webp")
        if (!file.isFile) {
            val bitmap = try {
                render()
            } catch (e: Exception) {
                Log.w(TAG, "poster: can not draw $key of ${kind.id}", e)
                null
            } ?: return null
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val temp = File(dir, "$key.tmp")
            try {
                temp.outputStream().use { out ->
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
                }
                if (!temp.renameTo(file)) return null
            } catch (e: Exception) {
                Log.w(TAG, "poster: can not write $key of ${kind.id}", e)
                temp.delete()
                return null
            } finally {
                bitmap.recycle()
            }
        }
        return ChannelPostersProvider.uriOf(context, kind.id, file.name).toString()
    }

    /** Deletes the posters of the [kind] that are not in [usedUris] */
    fun cleanup(context: Context, kind: ChannelKind, usedUris: Collection<String?>) {
        val used = usedUris.mapNotNullTo(HashSet()) { it?.substringAfterLast('/') }
        kindDir(context, kind).listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    /** A square tile of a site: its favicon (or its first letter) on a color of its own, with its name below */
    fun siteTile(context: Context, host: String, favicon: Bitmap?, width: Int = SQUARE_SIDE, height: Int = SQUARE_SIDE): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(TILE_COLORS[(host.hashCode() and Int.MAX_VALUE) % TILE_COLORS.size])
        val captionHeight = height * 0.2f
        val side = min(width, height) * 0.42f
        val cx = width / 2f
        val cy = (height - captionHeight) / 2f + captionHeight * 0.25f
        if (favicon != null) {
            val plate = RectF(cx - side * 0.62f, cy - side * 0.62f, cx + side * 0.62f, cy + side * 0.62f)
            canvas.drawRoundRect(plate, side * 0.18f, side * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            //small favicons are drawn at a whole multiple of their size, so they stay sharp enough
            val scale = min(side / favicon.width, side / favicon.height)
            val drawScale = if (scale >= 2f) scale.toInt().toFloat() else scale
            val w = favicon.width * drawScale
            val h = favicon.height * drawScale
            canvas.drawBitmap(favicon, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            val letter = host.removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = side * 1.3f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val bounds = Rect()
            paint.getTextBounds(letter, 0, letter.length, bounds)
            canvas.drawText(letter, cx, cy + bounds.height() / 2f, paint)
        }
        drawCaption(canvas, host.removePrefix("www."), width, height, captionHeight, 1)
        return bitmap
    }

    /** A wide tile with the [icon] of a kind of file and its [name] (up to two lines) */
    fun fileTile(context: Context, @DrawableRes icon: Int, name: String, colorSeed: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDE_WIDTH, WIDE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(TILE_COLORS[(colorSeed.hashCode() and Int.MAX_VALUE) % TILE_COLORS.size])
        val captionHeight = WIDE_HEIGHT * 0.34f
        //the icons tint themselves with theme attributes
        val drawable = AppCompatResources.getDrawable(ContextThemeWrapper(context, R.style.Theme_TVBro), icon)?.mutate()
        if (drawable != null) {
            drawable.setTint(Color.WHITE)
            val side = (WIDE_HEIGHT * 0.36f).toInt()
            val left = (WIDE_WIDTH - side) / 2
            val top = ((WIDE_HEIGHT - captionHeight - side) / 2 + WIDE_HEIGHT * 0.04f).toInt()
            drawable.setBounds(left, top, left + side, top + side)
            drawable.draw(canvas)
        }
        drawCaption(canvas, name, WIDE_WIDTH, WIDE_HEIGHT, captionHeight, 2)
        return bitmap
    }

    /** The top of a page thumbnail cut to 16:9 */
    fun wideCrop(thumbnail: Bitmap): Bitmap {
        val srcHeight = min(thumbnail.height, thumbnail.width * WIDE_HEIGHT / WIDE_WIDTH)
        val srcWidth = min(thumbnail.width, thumbnail.height * WIDE_WIDTH / WIDE_HEIGHT)
        val left = (thumbnail.width - srcWidth) / 2
        val bitmap = Bitmap.createBitmap(WIDE_WIDTH, WIDE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(thumbnail, Rect(left, 0, left + srcWidth, srcHeight),
            Rect(0, 0, WIDE_WIDTH, WIDE_HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    /** Fits a picture (the thumbnail of an image or a video) into a wide tile, cut at its middle */
    fun wideCover(picture: Bitmap): Bitmap {
        val srcWidth = min(picture.width, picture.height * WIDE_WIDTH / WIDE_HEIGHT)
        val srcHeight = min(picture.height, picture.width * WIDE_HEIGHT / WIDE_WIDTH)
        val left = (picture.width - srcWidth) / 2
        val top = (picture.height - srcHeight) / 2
        val bitmap = Bitmap.createBitmap(WIDE_WIDTH, WIDE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(picture, Rect(left, top, left + srcWidth, top + srcHeight),
            Rect(0, 0, WIDE_WIDTH, WIDE_HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    fun wideSiteTile(context: Context, host: String, favicon: Bitmap?) = siteTile(context, host, favicon, WIDE_WIDTH, WIDE_HEIGHT)

    private fun drawCaption(canvas: Canvas, text: String, width: Int, height: Int, captionHeight: Float, maxLines: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = min(captionHeight / (maxLines + 0.6f), height * 0.085f)
        }
        val padding = (width * 0.06f).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 2 * padding)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(padding.toFloat(), height - captionHeight + (captionHeight - layout.height) / 2f - height * 0.02f)
        layout.draw(canvas)
        canvas.restore()
    }
}
