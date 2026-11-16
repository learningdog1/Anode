package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt

interface UserRepository {
    suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean = false): Result<UserProfile>
    suspend fun updateOwnProfile(bio: String, signature: String, readme: String): Result<Unit>
    suspend fun getUserTopics(uid: Long, page: Int = 1): Result<Pair<List<TopicSummary>, Boolean>>
    suspend fun getUserReplies(uid: Long, page: Int = 1): Result<Pair<List<UserReplyExcerpt>, Boolean>>
    suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>>
    suspend fun setFollowed(targetUid: Long, follow: Boolean): Result<Unit>
    suspend fun getConnections(following: Boolean): Result<List<FloorAuthor>>
    suspend fun getBlockedUsers(): Result<List<FloorAuthor>>
    suspend fun blockUserByName(username: String): Result<Unit>
    suspend fun unblockUserById(targetUid: Long): Result<Unit>
}
