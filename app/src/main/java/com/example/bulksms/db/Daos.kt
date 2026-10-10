package com.example.bulksms.db

import androidx.room.*

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE, phone")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE optedOut = 0 AND (:groupName = '' OR groupName = :groupName) ORDER BY id")
    suspend fun eligible(groupName: String): List<ContactEntity>

    @Query("""
        SELECT * FROM contacts
        WHERE name LIKE '%' || :q || '%' OR phone LIKE '%' || :q || '%' OR groupName LIKE '%' || :q || '%'
        ORDER BY name COLLATE NOCASE, phone
    """)
    suspend fun search(q: String): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity): Long

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("UPDATE contacts SET optedOut = :value WHERE phone = :phone")
    suspend fun setOptOut(phone: String, value: Boolean)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT groupName FROM contacts ORDER BY groupName")
    suspend fun groups(): List<String>

    @Query("UPDATE contacts SET groupName = :newName WHERE groupName = :oldName")
    suspend fun renameGroup(oldName: String, newName: String)

    @Query("DELETE FROM contacts WHERE groupName = :g")
    suspend fun deleteGroup(g: String)

    @Query("DELETE FROM contacts WHERE id NOT IN (SELECT MIN(id) FROM contacts GROUP BY phone)")
    suspend fun deleteDuplicates()

    @Query("DELETE FROM contacts WHERE optedOut = 1")
    suspend fun deleteOptedOut()

    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE contacts SET groupName = :g WHERE id IN (:ids)")
    suspend fun setGroup(ids: List<Long>, g: String)

    @Query("UPDATE contacts SET optedOut = 1 WHERE phone IN (:phones)")
    suspend fun optOutMany(phones: List<String>): Int

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM contacts WHERE optedOut = 1")
    suspend fun optOutCount(): Int
}

@Dao
interface SendLogDao {
    @Insert
    suspend fun insert(log: SendLogEntity): Long

    @Query("SELECT * FROM send_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int = 500): List<SendLogEntity>

    @Query("SELECT COUNT(*) FROM send_logs WHERE status IN ('موفق','ارسال\u200cشده','تحویل\u200cشده','ارسال\u200cشده (تحویل نشد)')")
    suspend fun sentCount(): Int

    @Query("SELECT COUNT(*) FROM send_logs WHERE status LIKE 'ناموفق%'")
    suspend fun failedCount(): Int

    @Query("SELECT COUNT(*) FROM send_logs WHERE status = 'تحویل\u200cشده'")
    suspend fun deliveredCount(): Int

    @Query("SELECT COUNT(*) FROM send_logs WHERE timestamp >= :since AND status IN ('در حال ارسال','موفق','ارسال\u200cشده','تحویل\u200cشده','ارسال\u200cشده (تحویل نشد)')")
    suspend fun countSince(since: Long): Int

    @Query("SELECT * FROM send_logs WHERE status LIKE 'ناموفق%' ORDER BY timestamp DESC LIMIT 5000")
    suspend fun failedLogs(): List<SendLogEntity>

    @Query("UPDATE send_logs SET status = 'ارسال مجدد انجام شد' WHERE status LIKE 'ناموفق%'")
    suspend fun markFailedRetried()

    @Query("UPDATE send_logs SET status = :s WHERE id = :id")
    suspend fun setStatus(id: Long, s: String)

    @Query("SELECT * FROM send_logs WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<SendLogEntity>

    @Query("UPDATE send_logs SET status = :s WHERE id IN (:ids)")
    suspend fun setStatusMany(ids: List<Long>, s: String)

    @Query("UPDATE send_logs SET status = :s WHERE id = :id AND status NOT LIKE 'ناموفق%' AND status != 'تحویل\u200cشده'")
    suspend fun markSent(id: Long, s: String)

    @Query("UPDATE send_logs SET status = :s WHERE id = :id AND status NOT LIKE 'ناموفق%'")
    suspend fun markDelivered(id: Long, s: String)

    @Query("DELETE FROM send_logs")
    suspend fun clear()
}

@Dao
interface ScheduleDao {
    @Insert
    suspend fun insert(schedule: ScheduleEntity): Long

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Query("SELECT * FROM schedules ORDER BY scheduledAt DESC")
    suspend fun all(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): ScheduleEntity?

    @Query("UPDATE schedules SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM schedules")
    suspend fun clear()
}


@Dao
interface QueueDao {
    @Insert
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Query("SELECT * FROM queue_items WHERE state = 'PENDING' ORDER BY id LIMIT 1")
    suspend fun nextPending(): QueueItemEntity?

    @Query("UPDATE queue_items SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("SELECT COUNT(*) FROM queue_items WHERE state = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM queue_items WHERE state = 'DONE'")
    suspend fun doneCount(): Int

    @Query("SELECT COUNT(*) FROM queue_items")
    suspend fun totalCount(): Int

    @Query("DELETE FROM queue_items")
    suspend fun clear()
}
