package com.example.bulksms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ExtraDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "extras.db", null, 1) {

    data class Campaign(val id: Long, val name: String, val created: Long, val total: Int, val group: String, val message: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaigns (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL, total INTEGER NOT NULL, grp TEXT NOT NULL, message TEXT NOT NULL)")
        db.execSQL("CREATE TABLE campaign_logs (campaign_id INTEGER NOT NULL, log_id INTEGER NOT NULL, PRIMARY KEY (campaign_id, log_id))")
        db.execSQL("CREATE TABLE contact_fields (phone TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, PRIMARY KEY (phone, k))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    companion object {
        @Volatile private var inst: ExtraDb? = null
        fun get(ctx: Context): ExtraDb = inst ?: synchronized(this) {
            inst ?: ExtraDb(ctx).also { inst = it }
        }
    }

    // ----- campaigns -----
    fun createCampaign(name: String, total: Int, group: String, message: String): Long {
        val cv = ContentValues().apply {
            put("name", name); put("created", System.currentTimeMillis()); put("total", total)
            put("grp", group); put("message", message)
        }
        return writableDatabase.insert("campaigns", null, cv)
    }

    fun campaigns(): List<Campaign> {
        val out = ArrayList<Campaign>()
        readableDatabase.rawQuery("SELECT id, name, created, total, grp, message FROM campaigns ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(Campaign(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3), c.getString(4), c.getString(5)))
            }
        }
        return out
    }

    fun deleteCampaign(id: Long) {
        val db = writableDatabase
        db.delete("campaign_logs", "campaign_id = ?", arrayOf(id.toString()))
        db.delete("campaigns", "id = ?", arrayOf(id.toString()))
    }

    fun addLog(campaignId: Long, logId: Long) {
        val cv = ContentValues().apply { put("campaign_id", campaignId); put("log_id", logId) }
        writableDatabase.insertWithOnConflict("campaign_logs", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun logIds(campaignId: Long): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT log_id FROM campaign_logs WHERE campaign_id = ?", arrayOf(campaignId.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    // ----- custom contact fields -----
    fun setField(phone: String, k: String, v: String) {
        val cv = ContentValues().apply { put("phone", phone); put("k", k); put("v", v) }
        writableDatabase.insertWithOnConflict("contact_fields", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun replaceFields(phone: String, map: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("contact_fields", "phone = ?", arrayOf(phone))
            for ((k, v) in map) {
                val cv = ContentValues().apply { put("phone", phone); put("k", k); put("v", v) }
                db.insertWithOnConflict("contact_fields", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun fieldsOf(phone: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        readableDatabase.rawQuery("SELECT k, v FROM contact_fields WHERE phone = ? ORDER BY k", arrayOf(phone)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    fun allFields(): Map<String, Map<String, String>> {
        val out = HashMap<String, MutableMap<String, String>>()
        readableDatabase.rawQuery("SELECT phone, k, v FROM contact_fields", null).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getString(0)) { LinkedHashMap() }[c.getString(1)] = c.getString(2)
            }
        }
        return out
    }
}
