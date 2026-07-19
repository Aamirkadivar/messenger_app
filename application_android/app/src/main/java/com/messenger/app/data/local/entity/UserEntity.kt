package com.messenger.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import com.messenger.app.data.model.UserStatus

/**
 * Room entity for users
 */
@Entity(
    tableName = "users",
    indices = [Index("email", unique = true), Index("username", unique = true)]
)
data class UserEntity(
    val id: String,
    val name: String,
    val username: String,
    val email: String,
    val avatarUrl: String? = null,
    val status: String, // Serialized UserStatus
    val lastSeen: Long? = null,
    val bio: String? = null,
    val publicKey: String? = null,
    val createdAt: Long? = null
) {
    fun getUserStatus(): UserStatus {
        return try {
            UserStatus.valueOf(status)
        } catch (e: Exception) {
            UserStatus.OFFLINE
        }
    }
}