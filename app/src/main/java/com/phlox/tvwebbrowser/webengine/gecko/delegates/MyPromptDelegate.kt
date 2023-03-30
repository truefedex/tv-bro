package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.text.format.DateFormat
import android.util.Log
import android.view.InflateException
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.mozilla.geckoview.*
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaCallback
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class MyPromptDelegate(private val webEngine: GeckoWebEngine): GeckoSession.PromptDelegate {
    private var filePrompt: FilePrompt? = null
    private var fileResponse: GeckoResult<PromptResponse>? = null

    companion object {
        val TAG: String = MyPromptDelegate::class.java.simpleName
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AlertPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue(prompt.dismiss())
        val builder = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok,  /* onClickListener */null)
        val res = GeckoResult<PromptResponse>()
        createStandardDialog(builder, prompt, res).show()
        return res
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue<PromptResponse>(prompt.dismiss())
        val builder: AlertDialog.Builder = AlertDialog.Builder(activity)
            .setTitle(R.string.before_unload_title)
            .setMessage(R.string.before_unload_message)

        val res = GeckoResult<PromptResponse>()

        val listener =
            DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    res.complete(prompt.confirm(AllowOrDeny.ALLOW))
                } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                    res.complete(prompt.confirm(AllowOrDeny.DENY))
                } else {
                    res.complete(prompt.dismiss())
                }
            }

        builder.setPositiveButton(R.string.before_unload_leave_page, listener)
        builder.setNegativeButton(R.string.before_unload_stay, listener)

        createStandardDialog(builder, prompt, res).show()
        return res
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue<PromptResponse>(prompt.dismiss())
        val builder: AlertDialog.Builder = AlertDialog.Builder(activity)
            .setTitle(R.string.repost_confirm_title)
            .setMessage(R.string.repost_confirm_message)

        val res = GeckoResult<PromptResponse>()

        val listener =
            DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    res.complete(prompt.confirm(AllowOrDeny.ALLOW))
                } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                    res.complete(prompt.confirm(AllowOrDeny.DENY))
                } else {
                    res.complete(prompt.dismiss())
                }
            }

        builder.setPositiveButton(R.string.repost_confirm_resend, listener)
        builder.setNegativeButton(android.R.string.cancel, listener)

        createStandardDialog(builder, prompt, res).show()
        return res
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ButtonPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue<PromptResponse>(prompt.dismiss())
        val builder =
            AlertDialog.Builder(activity).setTitle(prompt.title).setMessage(prompt.message)

        val res = GeckoResult<PromptResponse>()

        val listener =
            DialogInterface.OnClickListener { dialog, which ->
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    res.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE))
                } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                    res.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE))
                } else {
                    res.complete(prompt.dismiss())
                }
            }

        builder.setPositiveButton(android.R.string.ok, listener)
        builder.setNegativeButton(android.R.string.cancel, listener)

        createStandardDialog(builder, prompt, res).show()
        return res
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.TextPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue<PromptResponse>(prompt.dismiss())
        val builder = AlertDialog.Builder(activity)
        val container = addStandardLayout(builder, prompt.title!!, prompt.message)
        val editText = EditText(builder.context)
        editText.setText(prompt.defaultValue)
        container!!.addView(editText)

        val res = GeckoResult<PromptResponse>()

        builder
            .setNegativeButton(android.R.string.cancel,  /* listener */null)
            .setPositiveButton(
                android.R.string.ok
            ) { dialog, which -> res.complete(prompt.confirm(editText.text.toString())) }

        createStandardDialog(builder, prompt, res).show()
        return res
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AuthPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue<PromptResponse>(prompt.dismiss())
        val builder = AlertDialog.Builder(activity)
        val container = addStandardLayout(builder, prompt.title, prompt.message)

        val flags = prompt.authOptions.flags
        val level = prompt.authOptions.level
        val username: EditText?
        if (flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD == 0) {
            username = EditText(builder.context)
            username.setHint(R.string.username)
            username.setText(prompt.authOptions.username)
            container!!.addView(username)
        } else {
            username = null
        }

        val password = EditText(builder.context)
        password.setHint(R.string.password)
        password.setText(prompt.authOptions.password)
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        container!!.addView(password)

        if (level != AuthPrompt.AuthOptions.Level.NONE) {
            val secure = ImageView(builder.context)
            secure.setImageResource(android.R.drawable.ic_lock_lock)
            container!!.addView(secure)
        }

        val res = GeckoResult<PromptResponse>()

        builder
            .setNegativeButton(android.R.string.cancel,  /* listener */null)
            .setPositiveButton(
                android.R.string.ok
            ) { dialog, which ->
                if (flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD == 0) {
                    res.complete(
                        prompt.confirm(username!!.text.toString(), password.text.toString())
                    )
                } else {
                    res.complete(prompt.confirm(password.text.toString()))
                }
            }
        createStandardDialog(builder, prompt, res).show()

        return res
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ChoicePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val res = GeckoResult<PromptResponse>()
        onChoicePromptImpl(
            session, prompt.title, prompt.message, prompt.type, prompt.choices, prompt, res
        )
        return res
    }

    override fun onColorPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ColorPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue<PromptResponse>(prompt.dismiss())
        val builder = AlertDialog.Builder(activity)
        addStandardLayout(builder, prompt.title,  /* msg */null)

        val initial: Int = parseColor(
            prompt.defaultValue,  /* def */
            0
        )
        val adapter: ArrayAdapter<Int> =
            object : ArrayAdapter<Int>(builder.context, android.R.layout.simple_list_item_1) {
                private var mInflater: LayoutInflater? = null
                override fun getViewTypeCount(): Int {
                    return 2
                }

                override fun getItemViewType(position: Int): Int {
                    return if (getItem(position) == initial) 1 else 0
                }

                override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                    var view = view
                    if (mInflater == null) {
                        mInflater = LayoutInflater.from(builder.context)
                    }
                    val color = getItem(position)!!
                    if (view == null) {
                        view = mInflater!!.inflate(
                            if (color == initial) android.R.layout.simple_list_item_checked else android.R.layout.simple_list_item_1,
                            parent,
                            false
                        )
                    }
                    view!!.setBackgroundResource(android.R.drawable.editbox_background)
                    view!!.background.setColorFilter(color, PorterDuff.Mode.MULTIPLY)
                    return view!!
                }
            }

        adapter.addAll(
            -0xbbbc /* holo_red_light */,
            -0x340000 /* holo_red_dark */,
            -0x44cd /* holo_orange_light */,
            -0x7800 /* holo_orange_dark */,
            -0x663400 /* holo_green_light */,
            -0x996700 /* holo_green_dark */,
            -0xcc4a1b /* holo_blue_light */,
            -0xff6634 /* holo_blue_dark */,
            -0x559934 /* holo_purple */,
            -0x1 /* white */,
            -0x555556 /* lighter_gray */,
            -0xaaaaab /* darker_gray */,
            -0x1000000 /* black */
        )

        val list = ListView(builder.context)
        list.adapter = adapter
        builder.setView(list)

        val res = GeckoResult<PromptResponse>()

        val dialog = createStandardDialog(builder, prompt, res)
        list.onItemClickListener =
            OnItemClickListener { parent, v, position, id ->
                res.complete(
                    prompt.confirm(String.format("#%06x", 0xffffff and adapter.getItem(position)!!))
                )
                dialog.dismiss()
            }
        dialog.show()

        return res
    }

    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.DateTimePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue<PromptResponse>(prompt.dismiss())
        val format: String
        format = if (prompt.type == DateTimePrompt.Type.DATE) {
            "yyyy-MM-dd"
        } else if (prompt.type == DateTimePrompt.Type.MONTH) {
            "yyyy-MM"
        } else if (prompt.type == DateTimePrompt.Type.WEEK) {
            "yyyy-'W'ww"
        } else if (prompt.type == DateTimePrompt.Type.TIME) {
            "HH:mm"
        } else if (prompt.type == DateTimePrompt.Type.DATETIME_LOCAL) {
            "yyyy-MM-dd'T'HH:mm"
        } else {
            throw java.lang.UnsupportedOperationException()
        }

        val formatter = SimpleDateFormat(format, Locale.ROOT)
        val minDate = parseDate(
            formatter,
            prompt.minValue,  /* defaultToNow */
            false
        )
        val maxDate = parseDate(
            formatter,
            prompt.maxValue,  /* defaultToNow */
            false
        )
        val date = parseDate(
            formatter,
            prompt.defaultValue,  /* defaultToNow */
            true
        )
        val cal = formatter.calendar
        cal.time = date

        val builder = AlertDialog.Builder(activity)
        val inflater = LayoutInflater.from(builder.context)
        val datePicker: DatePicker?
        if (prompt.type == DateTimePrompt.Type.DATE || prompt.type == DateTimePrompt.Type.MONTH || prompt.type == DateTimePrompt.Type.WEEK || prompt.type == DateTimePrompt.Type.DATETIME_LOCAL) {
            val resId = builder
                .context
                .resources
                .getIdentifier("date_picker_dialog", "layout", "android")
            var picker: DatePicker? = null
            if (resId != 0) {
                try {
                    picker = inflater.inflate(resId,  /* root */null) as DatePicker
                } catch (e: ClassCastException) {
                } catch (e: InflateException) {
                }
            }
            if (picker == null) {
                picker = DatePicker(builder.context)
            }
            picker.init(
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH],  /* listener */
                null
            )
            if (minDate != null) {
                picker.minDate = minDate.time
            }
            if (maxDate != null) {
                picker.maxDate = maxDate.time
            }
            datePicker = picker
        } else {
            datePicker = null
        }

        val timePicker: TimePicker?
        if (prompt.type == DateTimePrompt.Type.TIME
            || prompt.type == DateTimePrompt.Type.DATETIME_LOCAL
        ) {
            val resId = builder
                .context
                .resources
                .getIdentifier("time_picker_dialog", "layout", "android")
            var picker: TimePicker? = null
            if (resId != 0) {
                try {
                    picker = inflater.inflate(resId,  /* root */null) as TimePicker
                } catch (e: ClassCastException) {
                } catch (e: InflateException) {
                }
            }
            if (picker == null) {
                picker = TimePicker(builder.context)
            }
            setTimePickerTime(picker, cal)
            picker.setIs24HourView(DateFormat.is24HourFormat(builder.context))
            timePicker = picker
        } else {
            timePicker = null
        }

        val container = addStandardLayout(builder, prompt.title,  /* msg */null)
        container!!.setPadding( /* left */0,  /* top */0,  /* right */0,  /* bottom */0)
        if (datePicker != null) {
            container.addView(datePicker)
        }
        if (timePicker != null) {
            container.addView(timePicker)
        }

        val res = GeckoResult<PromptResponse>()

        val listener =
            DialogInterface.OnClickListener { dialog, which ->
                if (which == DialogInterface.BUTTON_NEUTRAL) {
                    // Clear
                    res.complete(prompt.confirm(""))
                    return@OnClickListener
                }
                if (datePicker != null) {
                    cal[datePicker.year, datePicker.month] = datePicker.dayOfMonth
                }
                if (timePicker != null) {
                    setCalendarTime(
                        cal,
                        timePicker
                    )
                }
                res.complete(prompt.confirm(formatter.format(cal.time)))
            }
        builder
            .setNegativeButton(android.R.string.cancel,  /* listener */null)
            .setNeutralButton(R.string.clear, listener)
            .setPositiveButton(android.R.string.ok, listener)

        val dialog = createStandardDialog(builder, prompt, res)
        dialog.show()

        prompt.delegate = object : PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) {
                dialog.dismiss()
            }
        }
        return res
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val activity: Activity = webEngine.callback?.getActivity()
            ?: return GeckoResult.fromValue<PromptResponse>(prompt.dismiss())

        // Merge all given MIME types into one, using wildcard if needed.
        var mimeType: String? = null
        var mimeSubtype: String? = null
        if (prompt.mimeTypes != null) {
            for (rawType in prompt.mimeTypes!!) {
                val normalizedType = rawType.trim { it <= ' ' }.lowercase()
                val len = normalizedType.length
                var slash = normalizedType.indexOf('/')
                if (slash < 0) {
                    slash = len
                }
                val newType = normalizedType.substring(0, slash)
                val newSubtype = normalizedType.substring(Math.min(slash + 1, len))
                if (mimeType == null) {
                    mimeType = newType
                } else if (mimeType != newType) {
                    mimeType = "*"
                }
                if (mimeSubtype == null) {
                    mimeSubtype = newSubtype
                } else if (mimeSubtype != newSubtype) {
                    mimeSubtype = "*"
                }
            }
        }

        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = (mimeType ?: "*") + '/' + (mimeSubtype ?: "*")
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        if (prompt.type == FilePrompt.Type.MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        if (prompt.mimeTypes?.isNotEmpty() == true) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes)
        }

        val res = GeckoResult<PromptResponse>()

        if (webEngine.callback?.onShowFileChooser(intent) == true) {
            fileResponse = res
            filePrompt = prompt
            return res
        } else {
            Log.e(TAG, "Cannot launch activity")
            return GeckoResult.fromValue(prompt.dismiss())
        }
    }

    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.PopupPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    override fun onSharePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.SharePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        return super.onSharePrompt(session, prompt)
    }

    override fun onLoginSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        Log.i(TAG, "onLoginSave")
        return GeckoResult.fromValue(request.confirm(request.options[0]))
    }

    override fun onAddressSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        return super.onAddressSave(session, request)
    }

    override fun onCreditCardSave(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSaveOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        Log.i(TAG, "onCreditCardSave " + request.options[0].value)
        return null
    }

    override fun onLoginSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        return super.onLoginSelect(session, request)
    }

    override fun onCreditCardSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.CreditCardSelectOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        return super.onCreditCardSelect(session, request)
    }

    override fun onAddressSelect(
        session: GeckoSession,
        request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSelectOption>
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        return super.onAddressSelect(session, request)
    }

    private fun createStandardDialog(
        builder: AlertDialog.Builder,
        prompt: BasePrompt,
        response: GeckoResult<PromptResponse>
    ): AlertDialog {
        val dialog = builder.create()
        dialog.setOnDismissListener {
            if (!prompt.isComplete) {
                response.complete(prompt.dismiss())
            }
        }
        return dialog
    }

    private fun getViewPadding(builder: AlertDialog.Builder): Int {
        val attr = builder
            .context
            .obtainStyledAttributes(intArrayOf(android.R.attr.listPreferredItemPaddingLeft))
        val padding = attr.getDimensionPixelSize(0, 1)
        attr.recycle()
        return padding
    }

    private fun addStandardLayout(
        builder: AlertDialog.Builder, title: String?, msg: String?
    ): LinearLayout? {
        val scrollView = ScrollView(builder.context)
        val container = LinearLayout(builder.context)
        val horizontalPadding = getViewPadding(builder)
        val verticalPadding = if (msg == null || msg.isEmpty()) horizontalPadding else 0
        container.orientation = LinearLayout.VERTICAL
        container.setPadding( /* left */
            horizontalPadding,  /* top */verticalPadding,  /* right */
            horizontalPadding,  /* bottom */verticalPadding
        )
        scrollView.addView(container)
        builder.setTitle(title).setMessage(msg).setView(scrollView)
        return container
    }

    private class ModifiableChoice(val choice: ChoicePrompt.Choice) {
        var modifiableSelected: Boolean
        var modifiableLabel: String

        init {
            modifiableSelected = choice.selected
            modifiableLabel = choice.label
        }
    }

    private fun addChoiceItems(
        type: Int,
        list: ArrayAdapter<ModifiableChoice>,
        items: Array<ChoicePrompt.Choice>,
        indent: String?
    ) {
        if (type == ChoicePrompt.Type.MENU) {
            for (item in items) {
                list.add(ModifiableChoice(item))
            }
            return
        }
        for (item in items) {
            val modItem = ModifiableChoice(item)
            val children = item.items
            if (indent != null && children == null) {
                modItem.modifiableLabel = indent + modItem.modifiableLabel
            }
            list.add(modItem)
            if (children != null) {
                val newIndent: String?
                newIndent =
                    if (type == ChoicePrompt.Type.SINGLE || type == ChoicePrompt.Type.MULTIPLE) {
                        if (indent != null) indent + '\t' else "\t"
                    } else {
                        null
                    }
                addChoiceItems(type, list, children, newIndent)
            }
        }
    }

    private fun onChoicePromptImpl(
        session: GeckoSession,
        title: String?,
        message: String?,
        type: Int,
        choices: Array<ChoicePrompt.Choice>,
        prompt: ChoicePrompt,
        res: GeckoResult<PromptResponse>
    ) {
        val activity = webEngine.callback?.getActivity()
        if (activity == null) {
            res.complete(prompt.dismiss())
            return
        }
        val builder = AlertDialog.Builder(activity)
        addStandardLayout(builder, title, message)
        val list = ListView(builder.context)
        if (type == ChoicePrompt.Type.MULTIPLE) {
            list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        }
        val adapter: ArrayAdapter<ModifiableChoice> = object : ArrayAdapter<ModifiableChoice>(
            builder.context, android.R.layout.simple_list_item_1
        ) {
            private val TYPE_MENU_ITEM = 0
            private val TYPE_MENU_CHECK = 1
            private val TYPE_SEPARATOR = 2
            private val TYPE_GROUP = 3
            private val TYPE_SINGLE = 4
            private val TYPE_MULTIPLE = 5
            private val TYPE_COUNT = 6
            private var mInflater: LayoutInflater? = null
            private var mSeparator: View? = null
            override fun getViewTypeCount(): Int {
                return TYPE_COUNT
            }

            override fun getItemViewType(position: Int): Int {
                val item = getItem(position)
                return if (item!!.choice.separator) {
                    TYPE_SEPARATOR
                } else if (type == ChoicePrompt.Type.MENU) {
                    if (item.modifiableSelected) TYPE_MENU_CHECK else TYPE_MENU_ITEM
                } else if (item.choice.items != null) {
                    TYPE_GROUP
                } else if (type == ChoicePrompt.Type.SINGLE) {
                    TYPE_SINGLE
                } else if (type == ChoicePrompt.Type.MULTIPLE) {
                    TYPE_MULTIPLE
                } else {
                    throw UnsupportedOperationException()
                }
            }

            override fun isEnabled(position: Int): Boolean {
                val item = getItem(position)
                return (!item!!.choice.separator
                        && !item.choice.disabled
                        && (type != ChoicePrompt.Type.SINGLE && type != ChoicePrompt.Type.MULTIPLE
                        || item.choice.items == null))
            }

            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                var view = view
                val itemType = getItemViewType(position)
                val layoutId: Int
                if (itemType == TYPE_SEPARATOR) {
                    if (mSeparator == null) {
                        mSeparator = View(context)
                        mSeparator!!.layoutParams = AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            2,
                            itemType
                        )
                        val attr =
                            context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
                        mSeparator!!.setBackgroundResource(attr.getResourceId(0, 0))
                        attr.recycle()
                    }
                    return mSeparator!!
                } else if (itemType == TYPE_MENU_ITEM) {
                    layoutId = android.R.layout.simple_list_item_1
                } else if (itemType == TYPE_MENU_CHECK) {
                    layoutId = android.R.layout.simple_list_item_checked
                } else if (itemType == TYPE_GROUP) {
                    layoutId = android.R.layout.preference_category
                } else if (itemType == TYPE_SINGLE) {
                    layoutId = android.R.layout.simple_list_item_single_choice
                } else if (itemType == TYPE_MULTIPLE) {
                    layoutId = android.R.layout.simple_list_item_multiple_choice
                } else {
                    throw UnsupportedOperationException()
                }
                if (view == null) {
                    if (mInflater == null) {
                        mInflater = LayoutInflater.from(builder.context)
                    }
                    view = mInflater!!.inflate(layoutId, parent, false)
                }
                val item = getItem(position)
                val text = view as TextView?
                text!!.isEnabled = !item!!.choice.disabled
                text.text = item.modifiableLabel
                if (view is CheckedTextView) {
                    val selected = item.modifiableSelected
                    if (itemType == TYPE_MULTIPLE) {
                        list.setItemChecked(position, selected)
                    } else {
                        view.isChecked = selected
                    }
                }
                return view!!
            }
        }
        addChoiceItems(type, adapter, choices,  /* indent */null)
        list.adapter = adapter
        builder.setView(list)
        val dialog: AlertDialog
        if (type == ChoicePrompt.Type.SINGLE || type == ChoicePrompt.Type.MENU) {
            dialog = createStandardDialog(builder, prompt, res)
            list.onItemClickListener =
                OnItemClickListener { parent, v, position, id ->
                    val item = adapter.getItem(position)
                    if (type == ChoicePrompt.Type.MENU) {
                        val children = item!!.choice.items
                        if (children != null) {
                            // Show sub-menu.
                            dialog.setOnDismissListener(null)
                            dialog.dismiss()
                            onChoicePromptImpl(
                                session,
                                item.modifiableLabel,  /* msg */
                                null,
                                type,
                                children,
                                prompt,
                                res
                            )
                            return@OnItemClickListener
                        }
                    }
                    res.complete(prompt.confirm(item!!.choice))
                    dialog.dismiss()
                }
        } else if (type == ChoicePrompt.Type.MULTIPLE) {
            list.onItemClickListener =
                OnItemClickListener { parent, v, position, id ->
                    val item = adapter.getItem(position)
                    item!!.modifiableSelected = (v as CheckedTextView).isChecked
                }
            builder
                .setNegativeButton(android.R.string.cancel,  /* listener */null)
                .setPositiveButton(
                    android.R.string.ok
                ) { dialog, which ->
                    val len = adapter.count
                    val items = ArrayList<String>(len)
                    for (i in 0 until len) {
                        val item = adapter.getItem(i)
                        if (item!!.modifiableSelected) {
                            items.add(item.choice.id)
                        }
                    }
                    res.complete(prompt.confirm(items.toTypedArray()))
                }
            dialog = createStandardDialog(builder, prompt, res)
        } else {
            throw UnsupportedOperationException()
        }
        dialog.show()
        prompt.delegate = object : PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) {
                dialog.dismiss()
            }

            override fun onPromptUpdate(prompt: BasePrompt) {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
                val newPrompt = prompt as ChoicePrompt
                onChoicePromptImpl(
                    session,
                    newPrompt.title,
                    newPrompt.message,
                    newPrompt.type,
                    newPrompt.choices,
                    newPrompt,
                    res
                )
            }
        }
    }

    private fun parseColor(value: String?, def: Int): Int {
        return try {
            Color.parseColor(value)
        } catch (e: IllegalArgumentException) {
            def
        }
    }

    private fun parseDate(
        formatter: SimpleDateFormat, value: String?, defaultToNow: Boolean
    ): Date? {
        try {
            if (value != null && !value.isEmpty()) {
                return formatter.parse(value)
            }
        } catch (e: ParseException) {
        }
        return if (defaultToNow) Date() else null
    }

    private fun setTimePickerTime(picker: TimePicker, cal: Calendar) {
        if (Build.VERSION.SDK_INT >= 23) {
            picker.hour = cal[Calendar.HOUR_OF_DAY]
            picker.minute = cal[Calendar.MINUTE]
        } else {
            picker.currentHour = cal[Calendar.HOUR_OF_DAY]
            picker.currentMinute = cal[Calendar.MINUTE]
        }
    }

    private fun setCalendarTime(cal: Calendar, picker: TimePicker) {
        if (Build.VERSION.SDK_INT >= 23) {
            cal[Calendar.HOUR_OF_DAY] = picker.hour
            cal[Calendar.MINUTE] = picker.minute
        } else {
            cal[Calendar.HOUR_OF_DAY] = picker.currentHour
            cal[Calendar.MINUTE] = picker.currentMinute
        }
    }

    fun onFileCallbackResult(resultCode: Int, data: Intent?) {
        val res: GeckoResult<PromptResponse> = fileResponse ?: return
        fileResponse = null
        val prompt: FilePrompt = filePrompt ?: return
        filePrompt = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            res.complete(prompt.dismiss())
            return
        }
        val activity = webEngine.callback?.getActivity() ?: return
        val uri = data.data
        val clip = data.clipData
        if (prompt.type == FilePrompt.Type.SINGLE || (prompt.type == FilePrompt.Type.MULTIPLE && clip == null)) {
            res.complete(prompt.confirm(activity, uri!!))
        } else if (prompt.type == FilePrompt.Type.MULTIPLE) {
            val count = clip!!.itemCount
            val uris = ArrayList<Uri>(count)
            for (i in 0 until count) {
                uris.add(clip.getItemAt(i).uri)
            }
            res.complete(prompt.confirm(activity, uris.toTypedArray()))
        }
    }

    fun onPermissionPrompt(
        session: GeckoSession?,
        title: String?,
        perm: ContentPermission?
    ): GeckoResult<Int>? {
        val activity: Activity? = webEngine.callback?.getActivity()
        val res = GeckoResult<Int>()
        if (activity == null) {
            res.complete(ContentPermission.VALUE_PROMPT)
            return res
        }
        val builder = AlertDialog.Builder(activity)
        builder
            .setTitle(title)
            .setNegativeButton(
                android.R.string.cancel
            ) { dialog, which -> res.complete(ContentPermission.VALUE_DENY) }
            .setPositiveButton(
                android.R.string.ok
            ) { dialog, which -> res.complete(ContentPermission.VALUE_ALLOW) }
        val dialog = builder.create()
        dialog.show()
        return res
    }

    fun onSlowScriptPrompt(
        geckoSession: GeckoSession?, title: String?, reportAction: GeckoResult<SlowScriptResponse?>
    ) {
        val activity: Activity = webEngine.callback?.getActivity() ?: return
        val builder = AlertDialog.Builder(activity)
        builder
            .setTitle(title)
            .setNegativeButton(
                activity.getString(R.string.wait)
            ) { dialog, which -> reportAction.complete(SlowScriptResponse.CONTINUE) }
            .setPositiveButton(
                activity.getString(R.string.stop)
            ) { dialog, which -> reportAction.complete(SlowScriptResponse.STOP) }
        val dialog = builder.create()
        dialog.show()
    }

    private fun addMediaSpinner(
        context: Context,
        container: ViewGroup?,
        sources: Array<PermissionDelegate.MediaSource>,
        sourceNames: Array<String>?
    ): Spinner? {
        val adapter: ArrayAdapter<PermissionDelegate.MediaSource> = object :
            ArrayAdapter<PermissionDelegate.MediaSource>(
                context,
                android.R.layout.simple_spinner_item
            ) {
            private fun convertView(position: Int, view: View): View {
                val item = getItem(position)
                (view as TextView).text = sourceNames?.get(position) ?: item!!.name
                return view
            }

            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                return convertView(position, super.getView(position, view, parent))
            }

            override fun getDropDownView(position: Int, view: View?, parent: ViewGroup): View {
                return convertView(position, super.getDropDownView(position, view, parent))
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        adapter.addAll(*sources)
        val spinner = Spinner(context)
        spinner.adapter = adapter
        spinner.setSelection(0)
        container!!.addView(spinner)
        return spinner
    }

    fun onMediaPrompt(
        session: GeckoSession?,
        title: String?,
        video: Array<PermissionDelegate.MediaSource>?,
        audio: Array<PermissionDelegate.MediaSource>?,
        videoNames: Array<String>?,
        audioNames: Array<String>?,
        callback: MediaCallback
    ) {
        val activity: Activity? = webEngine.callback?.getActivity()
        if (activity == null || video == null && audio == null) {
            callback.reject()
            return
        }
        val builder = AlertDialog.Builder(activity)
        val container = addStandardLayout(builder, title,  null)
        val videoSpinner: Spinner? = if (video != null) {
            addMediaSpinner(builder.context, container, video, videoNames)
        } else {
            null
        }
        val audioSpinner: Spinner? = if (audio != null) {
            addMediaSpinner(builder.context, container, audio, audioNames)
        } else {
            null
        }
        builder
            .setNegativeButton(android.R.string.cancel,  null)
            .setPositiveButton(
                android.R.string.ok
            ) { dialog, which ->
                val video =
                    if (videoSpinner != null) videoSpinner.selectedItem as PermissionDelegate.MediaSource else null
                val audio =
                    if (audioSpinner != null) audioSpinner.selectedItem as PermissionDelegate.MediaSource else null
                callback.grant(video, audio)
            }
        val dialog = builder.create()
        dialog.setOnDismissListener { callback.reject() }
        dialog.show()
    }

    fun onMediaPrompt(
        session: GeckoSession?,
        title: String?,
        video: Array<PermissionDelegate.MediaSource>?,
        audio: Array<PermissionDelegate.MediaSource>?,
        callback: MediaCallback
    ) {
        onMediaPrompt(session, title, video, audio, null, null, callback)
    }
}