package com.nodeseek.app.domain.ssh

/**
 * Thrown when a server's `jumpHostId` points to itself or forms a direct/transitive cycle
 * across saved SSH server profiles (e.g. `A -> B -> A` or `A -> B -> C -> A`).
 */
class SshJumpHostCycleException(
    val cycleServerIds: List<String>,
    message: String,
) : IllegalArgumentException(message)

/**
 * Identifies which phase failed during a ProxyJump / jump-host SSH connection attempt.
 */
enum class SshJumpConnectionStage {
    /** Failed while authenticating, handshaking, or opening port-forwarding on the jump host. */
    JUMP_HOST,

    /** Jump host connected, but establishing the forwarded SSH session to the target failed. */
    TARGET_VIA_JUMP,
}

/**
 * Domain exception distinguishing "跳板机连接失败" from "目标经跳板连接失败".
 */
class SshJumpHostConnectionException(
    val stage: SshJumpConnectionStage,
    val hopServerId: String? = null,
    val hopDisplayName: String,
    val hopEndpoint: String,
    val viaDisplayName: String? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Validates jump-host references (self-reference prohibition, cycle detection, multi-hop bounds)
 * and resolves ordered ProxyJump chains with decrypted credentials from [SshVaultRepository].
 */
object SshJumpHostResolver {
    const val MAX_JUMP_CHAIN_DEPTH: Int = 8

    fun normalizeJumpHostId(jumpHostId: String?): String? =
        jumpHostId?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Validates assigning [jumpHostId] to [serverId] against [servers].
     *
     * - `null` or blank [jumpHostId] means direct connection ("直连") and is always valid.
     * - Rejects self-reference (`serverId == jumpHostId`) with [SshJumpHostCycleException].
     * - Rejects missing [jumpHostId] or missing intermediate hops with [IllegalArgumentException].
     * - Rejects direct or multi-hop cycles (`A -> B -> A`, `A -> B -> C -> A`, etc.) with [SshJumpHostCycleException].
     */
    fun validateJumpHostSelection(
        serverId: String?,
        jumpHostId: String?,
        servers: List<SshServerProfile>,
    ) {
        val cleanJumpId = normalizeJumpHostId(jumpHostId) ?: return
        val cleanSelfId = serverId?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanSelfId != null && cleanSelfId == cleanJumpId) {
            val selfLabel = servers.firstOrNull { it.id == cleanSelfId }?.displayName ?: cleanSelfId
            throw SshJumpHostCycleException(
                cycleServerIds = listOf(cleanSelfId, cleanJumpId),
                message = "跳板机不能选择当前主机自身（$selfLabel）",
            )
        }

        val byId = servers.associateBy { it.id }
        if (cleanJumpId !in byId) {
            throw IllegalArgumentException("所选跳板机不存在或已被删除")
        }

        val visitedOrder = mutableListOf<String>()
        if (cleanSelfId != null) {
            visitedOrder.add(cleanSelfId)
        }

        var cursorId: String? = cleanJumpId
        while (cursorId != null) {
            if (cursorId in visitedOrder) {
                val cycleStart = visitedOrder.indexOf(cursorId)
                val cycleIds = visitedOrder.subList(cycleStart, visitedOrder.size) + cursorId
                val cycleLabel = cycleIds.joinToString(" -> ") { id ->
                    byId[id]?.displayName ?: id
                }
                throw SshJumpHostCycleException(
                    cycleServerIds = cycleIds,
                    message = "检测到跳板机循环引用：$cycleLabel",
                )
            }

            visitedOrder.add(cursorId)
            val hopCount = if (cleanSelfId != null) visitedOrder.size - 1 else visitedOrder.size
            if (hopCount > MAX_JUMP_CHAIN_DEPTH) {
                throw IllegalArgumentException("跳板机链路层级过深（最多支持 $MAX_JUMP_CHAIN_DEPTH 级跳板）")
            }

            val currentProfile = byId[cursorId]
                ?: throw IllegalArgumentException("跳板链路中的主机不存在或已被删除（ID: $cursorId）")

            cursorId = normalizeJumpHostId(currentProfile.jumpHostId)
        }
    }

    /**
     * Returns `true` if selecting [candidateJumpHostId] as the jump host for [serverId]
     * would result in a self-reference or cycle within [servers].
     */
    fun wouldCreateCycle(
        serverId: String?,
        candidateJumpHostId: String,
        servers: List<SshServerProfile>,
    ): Boolean {
        val cleanCandidateId = normalizeJumpHostId(candidateJumpHostId) ?: return false
        val cleanSelfId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanSelfId != null && cleanSelfId == cleanCandidateId) return true

        val byId = servers.associateBy { it.id }
        val visited = LinkedHashSet<String>()
        if (cleanSelfId != null) {
            visited.add(cleanSelfId)
        }

        var cursorId: String? = cleanCandidateId
        var steps = 0
        while (cursorId != null) {
            if (!visited.add(cursorId)) return true
            steps++
            if (steps > MAX_JUMP_CHAIN_DEPTH) return true
            val profile = byId[cursorId] ?: return false
            cursorId = normalizeJumpHostId(profile.jumpHostId)
        }
        return false
    }

    /**
     * Resolves the ordered list of jump hosts for [target] from **outermost** (connected first)
     * to **innermost** (connected right before [target]).
     *
     * Returns `emptyList()` when [target] connects directly (`jumpHostId == null`).
     *
     * @throws SshJumpHostCycleException if a self-reference or cycle exists in [servers].
     * @throws SshJumpHostConnectionException if a referenced jump host is missing from [servers].
     */
    fun resolveJumpChain(
        target: SshServerProfile,
        servers: List<SshServerProfile>,
    ): List<SshServerProfile> {
        val firstJumpId = normalizeJumpHostId(target.jumpHostId) ?: return emptyList()
        val byId = servers.associateBy { it.id }

        if (firstJumpId == target.id) {
            throw SshJumpHostCycleException(
                cycleServerIds = listOf(target.id, firstJumpId),
                message = "跳板机不能选择当前主机自身（${target.displayName}）",
            )
        }

        val visitedOrder = mutableListOf(target.id)
        val reverseChain = mutableListOf<SshServerProfile>()
        var cursorId: String? = firstJumpId

        while (cursorId != null) {
            if (cursorId in visitedOrder) {
                val cycleStart = visitedOrder.indexOf(cursorId)
                val cycleIds = visitedOrder.subList(cycleStart, visitedOrder.size) + cursorId
                val cycleLabel = cycleIds.joinToString(" -> ") { id ->
                    if (id == target.id) target.displayName else byId[id]?.displayName ?: id
                }
                throw SshJumpHostCycleException(
                    cycleServerIds = cycleIds,
                    message = "检测到跳板机循环引用：$cycleLabel",
                )
            }

            val hopProfile = byId[cursorId]
                ?: throw SshJumpHostConnectionException(
                    stage = SshJumpConnectionStage.JUMP_HOST,
                    hopServerId = cursorId,
                    hopDisplayName = cursorId,
                    hopEndpoint = cursorId,
                    message = "跳板机连接失败：找不到配置的跳板机（可能已被删除），请重新编辑主机「${target.displayName}」",
                )

            visitedOrder.add(cursorId)
            if (reverseChain.size >= MAX_JUMP_CHAIN_DEPTH) {
                throw SshJumpHostConnectionException(
                    stage = SshJumpConnectionStage.JUMP_HOST,
                    hopServerId = hopProfile.id,
                    hopDisplayName = hopProfile.displayName,
                    hopEndpoint = "${hopProfile.host}:${hopProfile.port}",
                    message = "跳板机连接失败：跳板链路超过最大支持层级（$MAX_JUMP_CHAIN_DEPTH 级）",
                )
            }

            reverseChain.add(hopProfile)
            cursorId = normalizeJumpHostId(hopProfile.jumpHostId)
        }

        return reverseChain.asReversed()
    }

    /**
     * Resolves any configured jump-host chain from [vaultRepository], decrypts credentials for
     * every hop and the target [server], and returns a ready-to-execute [SshConnectionRequest].
     */
    suspend fun buildConnectionRequest(
        vaultRepository: SshVaultRepository,
        server: SshServerProfile,
        allowHostKeyMismatchOverride: Boolean = false,
        mismatchOverrideServerId: String? = null,
    ): SshConnectionRequest {
        val jumpProfiles = resolveJumpChain(
            target = server,
            servers = vaultRepository.serversFlow.value,
        )

        val jumpHops = jumpProfiles.map { jumpProfile ->
            val jumpCredentials = try {
                vaultRepository.decryptCredentials(jumpProfile)
            } catch (t: Throwable) {
                throw SshJumpHostConnectionException(
                    stage = SshJumpConnectionStage.JUMP_HOST,
                    hopServerId = jumpProfile.id,
                    hopDisplayName = jumpProfile.displayName,
                    hopEndpoint = "${jumpProfile.host}:${jumpProfile.port}",
                    message = "跳板机连接失败（${jumpProfile.displayName}）：无法解密跳板机凭据（${t.message ?: "密钥不可用"}）",
                    cause = t,
                )
            }
            val allowHopMismatch = allowHostKeyMismatchOverride &&
                (mismatchOverrideServerId == null || mismatchOverrideServerId == jumpProfile.id)
            SshJumpHop(
                serverId = jumpProfile.id,
                alias = jumpProfile.displayName,
                host = jumpProfile.host,
                port = jumpProfile.port,
                username = jumpProfile.username,
                authMethod = jumpProfile.authMethod,
                secret = jumpCredentials.secret,
                passphrase = jumpCredentials.passphrase,
                expectedHostKeyFingerprint = jumpProfile.knownHostKeyFingerprint,
                allowHostKeyMismatchOverride = allowHopMismatch,
            )
        }

        val targetCredentials = vaultRepository.decryptCredentials(server)
        val allowTargetMismatch = allowHostKeyMismatchOverride &&
            (mismatchOverrideServerId == null || mismatchOverrideServerId == server.id)

        return SshConnectionRequest(
            host = server.host,
            port = server.port,
            username = server.username,
            authMethod = server.authMethod,
            secret = targetCredentials.secret,
            passphrase = targetCredentials.passphrase,
            expectedHostKeyFingerprint = server.knownHostKeyFingerprint,
            allowHostKeyMismatchOverride = allowTargetMismatch,
            serverId = server.id,
            alias = server.displayName,
            jumpHostId = normalizeJumpHostId(server.jumpHostId),
            jumpHosts = jumpHops,
        )
    }
}
