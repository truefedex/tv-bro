package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.RelativeLayout
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.activity.main.dialogs.ShortcutDialog
import com.phlox.tvwebbrowser.databinding.ViewShortcutBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity


class ShortcutsSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ListView(context, attrs, defStyleAttr), AdapterView.OnItemClickListener {

    var settingsModel: SettingsModel
    val items = arrayOf(R.string.toggle_main_menu, R.string.navigate_back, R.string.navigate_home,
            R.string.refresh_page, R.string.voice_search)

    init {
        settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)

        selector = context.resources.getDrawable(R.drawable.list_item_bg_selector, null)
        adapter = ShortcutItemAdapter()
        onItemClickListener = this
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

/*    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Adjust width as necessary
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        var _widthMeasureSpec = widthMeasureSpec
        if (maxWidth > 0 && maxWidth < measuredWidth) {
            val measureMode = MeasureSpec.getMode(widthMeasureSpec)
            _widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, measureMode)
        }
        // Adjust height as necessary
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
        var _heightMeasureSpec = heightMeasureSpec
        if (maxHeight > 0 && maxHeight < measuredHeight) {
            val measureMode = MeasureSpec.getMode(heightMeasureSpec)
            _heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, measureMode)
        }
        super.onMeasure(_widthMeasureSpec, _heightMeasureSpec)
    }*/

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
        private var vb: ViewShortcutBinding

        init {
            vb = ViewShortcutBinding.inflate(LayoutInflater.from(context), this)
        }

        fun bind(position: Int, titleRes: Int) {
            val shortcut = ShortcutMgr.getInstance().findForId(position)

            vb.tvTitle.setText(titleRes)
            vb.tvKey.text = if (shortcut == null || shortcut.keyCode == 0)
                context.getString(R.string.not_set)
            else
                KeyEvent.keyCodeToString(shortcut.keyCode)
        }
    }
}