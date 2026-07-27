package com.messenger.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Group chat models, mirroring back-end/handlers/group.go.
 *
 * Roles are plain strings on the wire ("admin" / "member"); [GroupRole] wraps
 * them for use in the UI without letting an unknown server value crash
 * deserialization.
 */

enum class GroupRole(val id: String) {
    ADMIN("admin"),
    MEMBER("member");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: MEMBER
    }
}

@Serializable
data class GroupMemberDto(
    val id: String,
    val email: String = "",
    val username: String = "",
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: String = GroupRole.MEMBER.id,
    @SerialName("joined_at") val joinedAt: String? = null
) {
    val groupRole: GroupRole get() = GroupRole.fromId(role)

    /** Best available human-readable name. */
    fun bestName(): String =
        displayName?.takeIf { it.isNotBlank() }
            ?: username.takeIf { it.isNotBlank() }
            ?: email.takeIf { it.isNotBlank() }
            ?: "Unknown"
}

@Serializable
data class GroupDto(
    val id: String,
    val name: String = "",
    val type: String = "group",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val description: String = "",
    @SerialName("owner_id") val ownerId: String? = null,
    val members: List<GroupMemberDto> = emptyList(),
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("unread_count") val unreadCount: Long = 0,
    @SerialName("user_role") val userRole: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String = "",
    /** Must not include the creator - the server adds them as admin. */
    @SerialName("member_ids") val memberIds: List<String>,
    @SerialName("avatar_url") val avatarUrl: String = ""
)

@Serializable
data class GroupResponse(
    val message: String? = null,
    val data: GroupDto
)

@Serializable
data class GroupsListResponse(
    val data: List<GroupDto> = emptyList()
)

@Serializable
data class AddMembersRequest(
    @SerialName("member_ids") val memberIds: List<String>
)

@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    val description: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)
