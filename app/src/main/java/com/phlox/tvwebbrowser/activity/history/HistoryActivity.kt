package com.phlox.tvwebbrowser.activity.history

import android.app.AlertDialog
import android.app.ListActivity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.support.v7.app.AppCompatActivity
import android.view.*
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.PopupMenu
import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.utils.BaseAnimationListener
import com.phlox.tvwebbrowser.utils.Utils
import kotlinx.android.synthetic.main.activity_history.*


/**
 * Created by fedex on 29.12.16.
 */

class HistoryActivity : AppCompatActivity(), AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    private var ibDelete: ImageButton? = null
    private var adapter: HistoryAdapter? = null
    private var asql: ASQL? = null
    private var loading = false
    private var searchQuery = ""

    private val sqlCallback = ASQL.ResultCallback<List<HistoryItem>> { result, error ->
        loading = false
        if (result != null) {
            if (result.isEmpty()) return@ResultCallback
            adapter!!.addItems(result)
            listView.requestFocus()
        } else {
            Utils.showToast(this@HistoryActivity, R.string.error)
        }
    }

    internal var onListScrollListener: AbsListView.OnScrollListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {

        }

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
            if (totalItemCount != 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 1 && "" == searchQuery) {
                loadItems(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        ibDelete = findViewById(R.id.ibDelete)

        adapter = HistoryAdapter()
        listView.adapter = adapter
        asql = ASQL.getDefault(this)

        listView.setOnScrollListener(onListScrollListener)
        listView.onItemClickListener = this
        listView.onItemLongClickListener = this

        loadItems(false)
    }

    private fun showDeleteDialog(deleteAll: Boolean) {
        val items = if (deleteAll) adapter!!.items else adapter!!.selectedItems
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(if (deleteAll) R.string.msg_delete_history_all else R.string.msg_delete_history)
                .setPositiveButton(android.R.string.yes) { dialogInterface, i ->
                    asql!!.delete(items) { result, exception ->
                        if (exception != null) {
                            Utils.showToast(this@HistoryActivity, R.string.error)
                        } else {
                            adapter!!.remove(items)
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
                    initiateVoiceSearch()
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
                    searchQuery = matches[0]
                    loadItems(true)
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
            asql!!.delete(v.historyItem) { result, exception ->
                if (exception != null) {
                    Utils.showToast(this@HistoryActivity, R.string.error)
                } else {
                    adapter!!.remove(v.historyItem!!)
                }
            }
            true
        }
        pm.show()
    }

    private fun initiateVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speak))
        startActivityForResult(intent, VOICE_SEARCH_REQUEST_CODE)
    }

    private fun loadItems(eraseOldResults: Boolean) {
        if (loading) {
            return
        }
        loading = true
        if (eraseOldResults) {
            adapter!!.erase()
        }
        if ("" == searchQuery) {
            asql!!.queryAll(HistoryItem::class.java, "SELECT * FROM history ORDER BY time DESC LIMIT 100 OFFSET ?",
                    sqlCallback, java.lang.Long.toString(adapter!!.realCount))
        } else {
            val search = "%$searchQuery%"
            asql!!.queryAll(HistoryItem::class.java, "SELECT * FROM history WHERE (title LIKE ?) OR (url LIKE ?) ORDER BY time DESC LIMIT 100",
                    sqlCallback, search, search)
        }
    }

    fun onClearHistoryClick(view: View) {
        showDeleteDialog(true)
    }

    fun onClearHistoryItemsClick(view: View) {
        showDeleteDialog(false)
    }

    companion object {
        private val VOICE_SEARCH_REQUEST_CODE = 10001

        val KEY_URL = "url"
    }
}
