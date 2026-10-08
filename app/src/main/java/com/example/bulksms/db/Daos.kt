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

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM contacts WHERE optedOut = 1")
    suspend fun optOutCount(): Int
}

@Dao
interface SendLogDao {
    @Insert
    suspend fun insert(log: SendLogEntity)

    @Query("SELECT * FROM send_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int = 500): List<SendLogEntity>

    @Query("SELECT COUNT(*) FROM send_logs WHERE status = 'موفق'")
    suspend fun sentCount(): Int

    @Query("SELECT COUNT(*) FROM send_logs WHERE status LIKE 'ناموفق%'")
    suspend fun failedCount(): Int

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
