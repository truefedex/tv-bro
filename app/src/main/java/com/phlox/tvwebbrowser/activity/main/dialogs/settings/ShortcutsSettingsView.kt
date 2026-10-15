package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.RelativeLayout
import androidx.core.content.res.ResourcesCompat
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.dialogs.ShortcutDialog
import com.phlox.tvwebbrowser.databinding.ViewShortcutBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.activity


class ShortcutsSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ListView(context, attrs, defStyleAttr), AdapterView.OnItemClickListener {

    val items = Shortcut.entries.map { it.titleResId }

    init {
        selector = ResourcesCompat.getDrawable(context.resources, R.drawable.list_item_bg_selector, null)
        adapter = ShortcutItemAdapter()
        onItemClickListener = this
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val dialog = ShortcutDialog(context, Shortcut.entries[position])
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
        private var vb: ViewShortcutBinding =
            ViewShortcutBinding.inflate(LayoutInflater.from(context), this)

        fun bind(position: Int, titleRes: Int) {
            val binding = ShortcutMgr.getInstance(context).bindingOf(Shortcut.entries[position])

            vb.tvTitle.setText(titleRes)
            vb.tvKey.text = binding.toDisplayString(context)
        }
    }
}