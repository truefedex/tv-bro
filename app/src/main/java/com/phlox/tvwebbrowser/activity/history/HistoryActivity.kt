package com.phlox.tvwebbrowser.activity.history

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ActivityHistoryBinding
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.BaseAnimationListener
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Created by fedex on 29.12.16.
 */

class HistoryActivity : AppCompatActivity(), AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener{

    private lateinit var vb: ActivityHistoryBinding
    private var ibDelete: ImageButton? = null
    private var adapter: HistoryAdapter? = null
    private lateinit var historyModel: HistoryModel

    internal var onListScrollListener: AbsListView.OnScrollListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {

        }

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
            if (totalItemCount != 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 1 && "" == historyModel.searchQuery) {
                historyModel.loadItems(false, adapter!!.realCount)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(vb.root)

        historyModel = ActiveModelsRepository.get(HistoryModel::class, this)

        ibDelete = findViewById(R.id.ibDelete)

        adapter = HistoryAdapter()
        vb.listView.adapter = adapter

        vb.listView.setOnScrollListener(onListScrollListener)
        vb.listView.onItemClickListener = this
        vb.listView.onItemLongClickListener = this

        historyModel.lastLoadedItems.subscribe(this, false) {
            if (it.isEmpty()) return@subscribe
            adapter!!.addItems(it)
            vb.listView.requestFocus()
        }

        historyModel.loadItems(false)
    }

    private fun showDeleteDialog(deleteAll: Boolean) {
        val items = if (deleteAll) adapter!!.items else adapter!!.selectedItems
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(if (deleteAll) R.string.msg_delete_history_all else R.string.msg_delete_history)
                .setPositiveButton(android.R.string.yes) { dialogInterface, i ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        AppDatabase.db.historyDao().delete(*items.toTypedArray())
                        adapter!!.remove(items)
                    }
                }
                .setNeutralButton(android.R.string.cancel) { dialogInterface, i -> }
                .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_SEARCH -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    //nop
                } else if (event.action == KeyEvent.ACTION_UP) {
                    VoiceSearchHelper.initiateVoiceSearch(this, VOICE_SEARCH_REQUEST_CODE)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            VOICE_SEARCH_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    // Populate the wordsList with the String values the recognition engine thought it heard
                    val matches = data?.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS)
                    if (matches == null || matches.isEmpty()) {
                        Utils.showToast(this, getString(R.string.can_not_recognize))
                        return
                    }
                    adapter!!.erase()
                    historyModel.searchQuery = matches[0]
                    historyModel.loadItems(true)
                }
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        val hi = (view as HistoryItemView).historyItem
        if (hi!!.isDateHeader) return
        if (adapter!!.isMultiselectMode) {
            view.setSelection(!hi.selected)
            updateMenu()
        } else {
            val resultIntent = Intent()
            resultIntent.putExtra(KEY_URL, hi.url)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onItemLongClick(adapterView: AdapterView<*>, view: View, i: Int, l: Long): Boolean {
        if (adapter!!.isMultiselectMode) return false
        adapter!!.isMultiselectMode = true
        val v = view as HistoryItemView
        v.setSelection(true)
        updateMenu()
        return true
    }

    private fun updateMenu() {
        val selection = adapter!!.selectedItems
        if (selection.isEmpty()) {
            if (ibDelete!!.visibility == View.GONE) return
            val anim = AnimationUtils.loadAnimation(this, R.anim.right_menu_out_anim)
            anim.setAnimationListener(object : BaseAnimationListener() {
                override fun onAnimationEnd(animation: Animation) {
                    ibDelete!!.visibility = View.GONE
                }
            })
            ibDelete!!.startAnimation(anim)
        } else {
            if (ibDelete!!.visibility == View.VISIBLE) return
            ibDelete!!.visibility = View.VISIBLE
            ibDelete!!.startAnimation(AnimationUtils.loadAnimation(this, R.anim.right_menu_in_anim))
        }
    }

    override fun onBackPressed() {
        if (adapter!!.isMultiselectMode) {
            adapter!!.isMultiselectMode = false
            updateMenu()
            return
        }
        super.onBackPressed()
    }

    private fun showItemOptionsPopup(v: HistoryItemView) {
        val pm = PopupMenu(this, v, Gravity.BOTTOM)
        pm.menu.add(R.string.delete)
        pm.setOnMenuItemClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                AppDatabase.db.historyDao().delete(v.historyItem!!)
                adapter!!.remove(v.historyItem!!)
            }
            true
        }
        pm.show()
    }

    fun onClearHistoryClick(view: View) {
        showDeleteDialog(true)
    }

    fun onClearHistoryItemsClick(view: View) {
        showDeleteDialog(false)
    }

    companion object {
        private const val VOICE_SEARCH_REQUEST_CODE = 10001

        const val KEY_URL = "url"
    }
}
