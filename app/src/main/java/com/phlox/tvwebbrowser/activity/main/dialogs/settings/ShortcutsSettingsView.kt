package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.support.v4.app.FragmentActivity
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.dialogs.ShortcutDialog
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.activity
import kotlinx.android.synthetic.main.view_shortcut.view.*

class ShortcutsSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ListView(context, attrs, defStyleAttr), AdapterView.OnItemClickListener {

    var settingsViewModel: SettingsViewModel
    val items = arrayOf(R.string.toggle_main_menu, R.string.navigate_back, R.string.navigate_home,
            R.string.refresh_page, R.string.voice_search)

    init {
        settingsViewModel = ViewModelProviders.of(activity as FragmentActivity).get(SettingsViewModel::class.java)

        selector = context.resources.getDrawable(R.drawable.list_item_bg_selector, null)
        adapter = ShortcutItemAdapter()
        setOnItemClickListener(this)
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val dialog = ShortcutDialog(context,
                ShortcutMgr.getInstance()
                        .findForId(position)!!
        )
        dialog.setOnDismissListener {
            (adapter as BaseAdapter).notifyDataSetChanged()
        }
        dialog.show()
    }

    inner class ShortcutItemAdapter: BaseAdapter() {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = if (convertView != null) {
                convertView as ShortcutItemView
            } else {
                ShortcutItemView(context)
            }
            view.bind(position, items[position])
            return view
        }

        override fun getItem(position: Int): Any {
            return items[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getCount(): Int {
            return items.size
        }
    }

    inner class ShortcutItemView @JvmOverloads constructor(
            context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
    ) : RelativeLayout(context, attrs, defStyleAttr) {
        init {
            LayoutInflater.from(context).inflate(R.layout.view_shortcut, this, true)
        }

        fun bind(position: Int, titleRes: Int) {
            val shortcut = ShortcutMgr.getInstance().findForId(position)

            tvTitle.setText(titleRes)
            tvKey.text = if (shortcut == null || shortcut.keyCode == 0)
                context.getString(R.string.not_set)
            else
                KeyEvent.keyCodeToString(shortcut.keyCode)
        }
    }
}