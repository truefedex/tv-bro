package com.phlox.tvwebbrowser.activity.history

import com.phlox.tvwebbrowser.TVBro
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ActivityHistoryBinding
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.utils.BaseAnimationListener
import com.phlox.tvwebbrowser.utils.SystemBars
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration
import com.phlox.tvwebbrowser.widgets.datedlist.StickyHeaderDecoration
import com.phlox.tvwebbrowser.widgets.datedlist.StickyHeaderLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Created by fedex on 29.12.16.
 */

class HistoryActivity : AppCompatActivity(), HistoryAdapter.Callback {

    private lateinit var vb: ActivityHistoryBinding
    private var ibDelete: ImageButton? = null
    private lateinit var adapter: HistoryAdapter
    private lateinit var historyModel: HistoryModel
    private val history get() = TVBro.container.data.history
    private val voiceSearchHelper = VoiceSearchHelper(this)

    /** Back leaves the multiselect mode before it closes the screen */
    private val multiselectBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setMultiselectMode(false)
        }
    }

    private val onListScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            loadMoreIfNeeded()
        }
    }

    /** Loads the next page when the end of the list is shown */
    private fun loadMoreIfNeeded() {
        val layoutManager = vb.listView.layoutManager as LinearLayoutManager
        val count = adapter.itemCount
        if (count != 0 && layoutManager.findLastVisibleItemPosition() >= count - 2 && "" == historyModel.searchQuery) {
            historyModel.loadItems(false, adapter.items.size.toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(vb.root)
        SystemBars.matchIconsToTheme(window)

        historyModel = ActiveModelsRepository.get(HistoryModel::class, this)

        ibDelete = findViewById(R.id.ibDelete)

        adapter = HistoryAdapter(this)
        val stickyHeader = StickyHeaderDecoration(adapter)
        vb.listView.layoutManager = StickyHeaderLayoutManager(this, stickyHeader)
        vb.listView.itemAnimator = null
        vb.listView.addItemDecoration(ListDividerDecoration(vb.listView, adapter))
        vb.listView.addItemDecoration(stickyHeader)
        vb.listView.adapter = adapter
        vb.listView.addOnScrollListener(onListScrollListener)

        historyModel.lastLoadedItems.subscribe(this, false) {
            if (it.isEmpty()) return@subscribe
            val first = adapter.items.isEmpty()
            adapter.submitItems(adapter.items + it) {
                if (first) vb.listView.requestFocus()
            }
        }

        historyModel.loadItems(false)

        onBackPressedDispatcher.addCallback(this, multiselectBackCallback)
    }

    private fun setMultiselectMode(enabled: Boolean) {
        adapter.isMultiselectMode = enabled
        multiselectBackCallback.isEnabled = enabled
        updateMenu()
    }

    private fun showDeleteDialog(deleteAll: Boolean) {
        if (adapter.items.isEmpty() || (adapter.selectedItems.isEmpty() && !deleteAll)) return
        AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(if (deleteAll) R.string.msg_delete_history_all else R.string.msg_delete_history)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (deleteAll) {
                            history.deleteAll()
                            adapter.submitItems(emptyList())
                        } else {
                            val selected = adapter.selectedItems
                            history.delete(selected)
                            adapter.remove(selected) { updateMenu() }
                        }
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
                    voiceSearchHelper.initiateVoiceSearch(object : VoiceSearchHelper.Callback {
                        override fun onResult(text: String?) {
                            if (text == null) {
                                Utils.showToast(this@HistoryActivity, getString(R.string.can_not_recognize))
                                return
                            }
                            adapter.submitItems(emptyList())
                            historyModel.searchQuery = text
                            historyModel.loadItems(true)
                        }
                    })
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onItemClick(item: HistoryItem) {
        if (adapter.isMultiselectMode) {
            adapter.setSelected(item, !adapter.isSelected(item))
            updateMenu()
        } else {
            val resultIntent = Intent()
            resultIntent.putExtra(KEY_URL, item.url)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onItemLongClick(item: HistoryItem): Boolean {
        if (adapter.isMultiselectMode) return false
        setMultiselectMode(true)
        adapter.setSelected(item, true)
        updateMenu()
        return true
    }

    private fun updateMenu() {
        val selection = adapter.selectedItems
        if (selection.isEmpty()) {
            if (ibDelete!!.visibility == View.GONE) return
            if (ibDelete!!.hasFocus()) {
                //to the row next to the button, as when Left is pressed on it
                val rect = Rect()
                ibDelete!!.getDrawingRect(rect)
                vb.root.offsetDescendantRectToMyCoords(ibDelete, rect)
                vb.root.offsetRectIntoDescendantCoords(vb.listView, rect)
                vb.listView.requestFocus(View.FOCUS_LEFT, rect)
            }
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

    fun onClearHistoryClick(view: View) {
        showDeleteDialog(true)
    }

    fun onClearHistoryItemsClick(view: View) {
        showDeleteDialog(false)
    }

    companion object {

        const val KEY_URL = "url"
    }
}
