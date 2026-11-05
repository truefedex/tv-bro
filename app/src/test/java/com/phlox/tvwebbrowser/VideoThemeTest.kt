package com.phlox.tvwebbrowser

import android.view.ContextThemeWrapper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.activity.main.view.PlayerPanelView
import com.phlox.tvwebbrowser.activity.main.view.PlayerScreenView
import com.phlox.tvwebbrowser.activity.main.view.VideoOsdView
import com.phlox.tvwebbrowser.utils.themeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The overlays of the players over a video take the dark tokens in both themes (ThemeOverlay.TVBro.Video) */
@RunWith(RobolectricTestRunner::class)
class VideoThemeTest {
    private val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.AppTheme) }
        .setup().get()
    private val video = ContextThemeWrapper(activity, R.style.ThemeOverlay_TVBro_Video)

    private fun color(id: Int) = ContextCompat.getColor(activity, id)

    @Test
    fun theLightThemeGetsTheDarkTokensOverVideo() {
        assertNotEquals(color(R.color.ds_dark_menu), activity.themeColor(R.attr.tbColorDsMenu))
        assertEquals(0xFF2F2F2F.toInt(), video.themeColor(R.attr.tbColorDsMenu))
        assertEquals(0xFF1B1B1B.toInt(), video.themeColor(R.attr.tbColorDsSurface))
        assertEquals(0xFF323940.toInt(), video.themeColor(R.attr.tbColorDsControl))
        assertEquals(0xFF003E4F.toInt(), video.themeColor(R.attr.tbColorDsControlFocused))
        assertEquals(0xFFF8F9FA.toInt(), video.themeColor(R.attr.tbColorDsInk))
        assertEquals(0xFFBBBBBB.toInt(), video.themeColor(R.attr.tbColorDsInkFaint))
        assertEquals(0xFFFDFDFD.toInt(), video.themeColor(R.attr.tbColorDsIcon))
        //the tokens that are the same in both themes come from the activity theme
        assertEquals(0xFF5FACFF.toInt(), video.themeColor(R.attr.tbColorDsSelected))
        assertEquals(0xFFA5D0FF.toInt(), video.themeColor(R.attr.tbColorDsFocus))
    }

    @Test
    fun theViewsOverVideoAreMadeInTheOverlay() {
        //the context of a view with android:theme is a wrapper, not the activity
        val player = PlayerScreenView(video)
        assertEquals(View.GONE, player.visibility)
        val osd = VideoOsdView(video)
        osd.setInfo("Film", "TV Bro player · HLS · kino.example")
        val panel = PlayerPanelView(video)
        panel.open(PlayerPanelView.Page("Video settings", null, listOf(PlayerPanelView.Row(null, "Speed", value = "1×") {}), "Close"))
        assertEquals(View.VISIBLE, panel.visibility)
        panel.back()
        assertEquals(View.GONE, panel.visibility)
    }

    @Test
    @Config(qualifiers = "night")
    fun theNightThemeHasTheSameValues() {
        for (attr in listOf(R.attr.tbColorDsMenu, R.attr.tbColorDsSurface, R.attr.tbColorDsControl, R.attr.tbColorDsInk,
                R.attr.tbColorDsInkFaint, R.attr.tbColorDsIcon, R.attr.tbColorDsLine)) {
            assertEquals(activity.themeColor(attr), video.themeColor(attr))
        }
    }
}
