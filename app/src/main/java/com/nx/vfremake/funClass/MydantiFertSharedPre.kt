package com.nx.vfremake.funClass

import android.content.Context
import com.google.gson.Gson


class MydantiFertSharedPre(context: Context) {
    private val sharedPreferences = MySharedPreFun(context).getMydantiFertSettingsSharedPre()
    private val gson: Gson = Gson()

    // 保存数据
    fun saveTableRow(id: Int, tableRow: TableRow?) {
        val editor = sharedPreferences.edit()
        val key = "row_$id"
        val json = gson.toJson(tableRow)
        editor.putString(key, json)
        editor.apply()
    }

    // 获取数据
    fun getTableRow(id: Int): TableRow? {
        val key = "row_$id"
        val json = sharedPreferences.getString(key, null)
        if (json != null) {
            return gson.fromJson(json, TableRow::class.java)
        }
        return null
    }

    fun getTableRows(): List<TableRow> {
        val gson = Gson()
        // 所有行的 key 都是 "row_" + id
        val allIds = sharedPreferences.all.keys
        val tableRows = mutableListOf<TableRow>()

        for (id in allIds) {
            if (id.startsWith("row_")) {
                val json = sharedPreferences.getString(id, null)
                if (json != null) {
                    val row = gson.fromJson(json, TableRow::class.java)
                    tableRows.add(row)
                }
            }
        }
        return tableRows
    }

    // 添加删除行数据的方法
    fun deleteTableRow(id: Int) {
        val editor = sharedPreferences.edit()
        val key = "row_$id"
        editor.remove(key)
        editor.apply()
    }

}

class TableRow(
    var id: Int, var a: String, var b: String
)
