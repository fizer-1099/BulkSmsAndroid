package com.example.bulksms.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val phone: String,
    val groupName: String = "",
    val optedOut: Boolean = false
)

@Entity(tableName = "send_logs")
data class SendLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phone: String,
    val message: String,
    val groupName: String = "",
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupName: String = "",
    val message: String,
    val scheduledAt: Long,
    val intervalSeconds: Int = 3,
    val subscriptionId: Int = -1,
    val workRequestId: String = "",
    val status: String = "SCHEDULED"
)
