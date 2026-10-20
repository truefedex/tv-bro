package com.phlox.tvwebbrowser.activity.main.view

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.SiteConnection
import com.phlox.tvwebbrowser.utils.themeColor

/**
 * The site button of the TV bar and of the phone pill: sliders (the settings of this site), or the
 * warning sign in the danger color on a page without a secure connection. It has no badge: the counts
 * of blocked ads and pop-ups are in the rows of the site settings.
 */
fun ImageView.showSiteConnection(host: String, connection: SiteConnection) {
    if (connection.insecure) {
        setImageResource(R.drawable.ic_warning)
        imageTintList = ColorStateList.valueOf(context.themeColor(R.attr.tbColorDsDanger))
        contentDescription = context.getString(R.string.site_settings_insecure_of, host)
    } else {
        setImageResource(R.drawable.ic_site)
        //the icon's own tint (a null tint list would drop it)
        imageTintList = AppCompatResources.getColorStateList(context, R.color.icon_tint)
        contentDescription = if (host.isEmpty()) context.getString(R.string.site_settings) else
            context.getString(R.string.site_settings_of, host)
    }
}

/** The connection line of the header of the site settings: a lock, or the warning sign and the reason */
internal fun SiteConnection.reason(): Int? = when (this) {
    SiteConnection.HTTP -> R.string.connection_http_reason
    SiteConnection.CERTIFICATE_ERROR -> R.string.connection_certificate_reason
    else -> null
}
