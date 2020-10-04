package com.phlox.tvwebbrowser.model.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.phlox.tvwebbrowser.model.AdBlockItem

@Dao
interface AdBlockListDao {
    @Query("DELETE FROM adblocklist")
    suspend fun deleteAll()

    @Insert
    fun insert(item: AdBlockItem): Long

    @Query("SELECT * FROM adblocklist WHERE adblocklist.value = :host LIMIT 1")
    fun findFirstHostThatMatches(host: String): List<AdBlockItem>

    @Query("SELECT COUNT(*) FROM adblocklist")
    fun getCount(): Int
}