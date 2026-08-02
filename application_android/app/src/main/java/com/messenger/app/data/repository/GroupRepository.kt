package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.model.AddMembersRequest
import com.messenger.app.data.model.CreateGroupRequest
import com.messenger.app.data.model.GroupDto
import com.messenger.app.data.model.GroupRole
import com.messenger.app.data.model.GroupSenderKeyEntryDto
import com.messenger.app.data.model.PublishSenderKeyRequest
import com.messenger.app.data.model.SenderKeyRecipientDto
import com.messenger.app.data.model.UpdateGroupRequest
import com.messenger.app.data.model.UpdateMemberRoleRequest
import com.messenger.app.data.remote.api.ChatApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Group chat operations, backed by back-end/handlers/group.go.
 *
 * Separate from [ChatRepository] because groups have their own lifecycle
 * (membership, roles, admin permissions) that has nothing to do with the
 * direct-message send/receive path.
 *
 * NOTE ON ENCRYPTION: group *messages* use a WhatsApp/Signal-style "Sender
 * Key" scheme - see [publishSenderKey]/[getSenderKeys] below - because the
 * pairwise X25519 crypto_box used for direct chats doesn't generalise to N
 * members. [ChatRepository] owns the actual key generation/rotation/message
 * encryption logic; this class only carries the wire calls.
 */
class GroupRepository(
    private val chatApiService: ChatApiService
) {
    companion object {
        private const val TAG = "GroupRepository"
        const val MAX_NAME_LENGTH = 255
    }

    private fun bearer(token: String) = "Bearer $token"

    /**
     * Creates a group. [memberIds] must not contain the current user - the
     * server adds the creator as admin and rejects nothing, but including
     * yourself is silently de-duplicated server-side.
     */
    suspend fun createGroup(
        token: String,
        name: String,
        memberIds: List<String>,
        description: String = "",
        avatarUrl: String = ""
    ): Result<GroupDto> = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        // Validated here as well as server-side so the UI can fail fast without
        // a round trip.
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Group name is required"))
        }
        if (trimmed.length > MAX_NAME_LENGTH) {
            return@withContext Result.failure(
                IllegalArgumentException("Group name must be $MAX_NAME_LENGTH characters or fewer")
            )
        }

        try {
            val response = chatApiService.createGroup(
                bearer(token),
                CreateGroupRequest(
                    name = trimmed,
                    description = description.trim(),
                    memberIds = memberIds.distinct(),
                    avatarUrl = avatarUrl
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.data)
            } else {
                Result.failure(Exception(response.errorMessage("Failed to create group")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createGroup error", e)
            Result.failure(e)
        }
    }

    suspend fun getGroups(token: String): Result<List<GroupDto>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getGroups(bearer(token))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.data)
            } else {
                Result.failure(Exception(response.errorMessage("Failed to load groups")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getGroups error", e)
            Result.failure(e)
        }
    }

    suspend fun getGroupInfo(token: String, chatId: String): Result<GroupDto> =
        withContext(Dispatchers.IO) {
            try {
                val response = chatApiService.getGroupInfo(bearer(token), chatId)
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!.data)
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to load group")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "getGroupInfo error", e)
                Result.failure(e)
            }
        }

    /** Admin only - the server returns 403 for non-admins. */
    suspend fun addMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (memberIds.isEmpty()) return@withContext Result.success(Unit)
            try {
                val response = chatApiService.addGroupMembers(
                    bearer(token), chatId, AddMembersRequest(memberIds.distinct())
                )
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to add members")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "addMembers error", e)
                Result.failure(e)
            }
        }

    /** Admin only. */
    suspend fun removeMember(token: String, chatId: String, memberId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val response = chatApiService.removeGroupMember(bearer(token), chatId, memberId)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to remove member")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "removeMember error", e)
                Result.failure(e)
            }
        }

    /**
     * Promotes a member to admin, or demotes an admin back to member.
     * Admin only; the server refuses to change the owner's role.
     */
    suspend fun setMemberRole(
        token: String,
        chatId: String,
        memberId: String,
        role: GroupRole
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.updateMemberRole(
                bearer(token), chatId, memberId, UpdateMemberRoleRequest(role.id)
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.errorMessage("Failed to update role")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "setMemberRole error", e)
            Result.failure(e)
        }
    }

    /** Owner only - irreversible, removes the group for every member. */
    suspend fun deleteGroup(token: String, chatId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val response = chatApiService.deleteGroup(bearer(token), chatId)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to delete group")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteGroup error", e)
                Result.failure(e)
            }
        }

    /** Admin only. */
    suspend fun updateGroup(
        token: String,
        chatId: String,
        name: String? = null,
        description: String? = null,
        avatarUrl: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.updateGroup(
                bearer(token), chatId, UpdateGroupRequest(name?.trim(), description?.trim(), avatarUrl)
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.errorMessage("Failed to update group")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateGroup error", e)
            Result.failure(e)
        }
    }

    /**
     * Leaves a group. The server refuses (403) if the caller is the only admin,
     * so surface that message rather than a generic failure.
     */
    suspend fun leaveGroup(token: String, chatId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val response = chatApiService.leaveGroup(bearer(token), chatId)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to leave group")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "leaveGroup error", e)
                Result.failure(e)
            }
        }

    /**
     * Distributes this device's per-recipient encrypted copies of its current
     * group Sender Key. [recipients] must already be encrypted client-side
     * (crypto_box, one ciphertext per member) - the server only relays blobs.
     */
    suspend fun publishSenderKey(
        token: String,
        chatId: String,
        keyVersion: Int,
        recipients: List<SenderKeyRecipientDto>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.publishSenderKey(
                bearer(token), chatId, PublishSenderKeyRequest(keyVersion, recipients)
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.errorMessage("Failed to publish sender key")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "publishSenderKey error", e)
            Result.failure(e)
        }
    }

    /** Every Sender Key distributed to the caller across this group's members, still encrypted. */
    suspend fun getSenderKeys(token: String, chatId: String): Result<List<GroupSenderKeyEntryDto>> =
        withContext(Dispatchers.IO) {
            try {
                val response = chatApiService.getSenderKeys(bearer(token), chatId)
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!.data)
                } else {
                    Result.failure(Exception(response.errorMessage("Failed to fetch sender keys")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "getSenderKeys error", e)
                Result.failure(e)
            }
        }
}

/**
 * The backend returns {"error": ..., "message": ...} on failure; prefer its
 * message over a bare HTTP code so validation problems are actionable.
 */
internal fun Response<*>.errorMessage(fallback: String): String {
    val body = runCatching { errorBody()?.string() }.getOrNull()
    val serverMessage = body
        ?.substringAfter("\"message\":\"", "")
        ?.substringBefore("\"", "")
        ?.takeIf { it.isNotBlank() }
    return serverMessage ?: "$fallback (${code()})"
}
