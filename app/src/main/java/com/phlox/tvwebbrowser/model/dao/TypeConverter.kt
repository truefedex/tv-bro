package com.phlox.tvwebbrowser.model.dao

import androidx.room.TypeConverter
import com.phlox.tvwebbrowser.model.AdItemType

class TypeConverter {
    @TypeConverter
    fun fromAdItemType(value: AdItemType): Int{
        return value.ordinal
    }

    @TypeConverter
    fun toAdItemType(value: Int): AdItemType{
        return AdItemType.values()[value]
    }
}