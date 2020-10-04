package com.phlox.tvwebbrowser.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AdItemType { HOST }//other item types in future?

@Entity(tableName = "adblocklist",
        indices = [Index(value = ["type", "value"], name = "type_value_idx")])
data class AdBlockItem(@PrimaryKey(autoGenerate = true)
                       var id: Long = 0, val type: AdItemType, var value: String) {
    constructor(type: AdItemType) : this(0, type, "")
}