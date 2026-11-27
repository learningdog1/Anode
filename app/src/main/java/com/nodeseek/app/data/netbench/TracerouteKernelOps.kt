package com.nodeseek.app.data.netbench

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructCmsghdr
import android.system.StructMsghdr
import android.system.StructPollfd
import androidx.annotation.RequiresApi
import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import java.io.FileDescriptor
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class TracerouteProbeProtocol(val label: String) {
    ICMP("ICMP"),
    UDP("UDP"),
    TCP("TCP"),
}

data class TracerouteProbeReply(
    val kind: Kind,
    val responderIp: String?,
    val sequence: Int,
    val rttMs: Double?,
    val icmpType: Int? = null,
    val icmpCode: Int? = null,
    val protocol: TracerouteProbeProtocol = TracerouteProbeProtocol.ICMP,
) {
    enum class Kind {
        ECHO_REPLY,
        TIME_EXCEEDED,
        DEST_UNREACHABLE,
        TIMEOUT,
    }
}

/**
 * Testable OS boundary for Stage-1 per-hop TTL probing via unprivileged:
 * - `SOCK_DGRAM` `IPPROTO_ICMP` / `IPPROTO_ICMPV6` + `IP_RECVERR` / `MSG_ERRQUEUE`
 * - `SOCK_DGRAM` `IPPROTO_UDP` + `IP_TTL` + `IP_RECVERR` / `MSG_ERRQUEUE`
 * - `SOCK_STREAM` `IPPROTO_TCP` + `IP_TTL` + `IP_RECVERR` non-blocking SYN probe
 * (API 31+ for ancillary `Os.recvmsg` error-queue decoding).
 */
interface TracerouteKernelOps {
    fun isSupported(): Boolean

    /**
     * Whether this implementation supports concurrent per-TTL probing across ICMP / UDP / TCP
     * sockets via [probeHop]. Legacy single-socket test doubles default to `false`.
     */
    val supportsConcurrentMultiProtocol: Boolean
        get() = false

    @Throws(Exception::class)
    fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?): FileDescriptor

    @Throws(Exception::class)
    fun sendEchoWithTtl(
        fd: FileDescriptor,
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        ttl: Int,
        identifier: Int,
        sequence: Int,
    )

    @Throws(Exception::class)
    fun awaitReplyOrError(
        fd: FileDescriptor,
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        identifier: Int,
        expectedSequence: Int,
        timeoutMs: Int,
    ): TracerouteProbeReply

    /**
     * Executes a single self-contained probe for `(ttl, sequence)` using [protocol].
     * Implementations supporting [supportsConcurrentMultiProtocol] open an isolated socket per probe
     * so concurrent TTL probes never contaminate each other's `sk_err` or `MSG_ERRQUEUE`.
     */
    @Throws(Exception::class)
    fun probeHop(
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        ttl: Int,
        identifier: Int,
        sequence: Int,
        timeoutMs: Int,
        channel: NetBenchSocketChannel?,
        protocol: TracerouteProbeProtocol = TracerouteProbeProtocol.ICMP,
        targetPort: Int = DEFAULT_TCP_PROBE_PORT,
    ): TracerouteProbeReply {
        val fd = openBoundSocket(ipFamily, channel)
        try {
            sendEchoWithTtl(
                fd = fd,
                targetAddress = targetAddress,
                ipFamily = ipFamily,
                ttl = ttl,
                identifier = identifier,
                sequence = sequence,
            )
            return awaitReplyOrError(
                fd = fd,
                targetAddress = targetAddress,
                ipFamily = ipFamily,
                identifier = identifier,
                expectedSequence = sequence,
                timeoutMs = timeoutMs,
            )
        } finally {
            closeQuietly(fd)
        }
    }

    fun closeQuietly(fd: FileDescriptor)

    companion object {
        const val DEFAULT_TCP_PROBE_PORT = 443
        const val BASE_UDP_TRACEROUTE_PORT = 33434
    }
}

/**
 * Pure Kotlin parser for Linux `sock_extended_err` + `SO_EE_OFFENDER` (`sockaddr_in`/`sockaddr_in6`)
 * delivered via `IP_RECVERR` / `IPV6_RECVERR` ancillary control messages in `MSG_ERRQUEUE`, plus
 * payload sequence correlation helpers for ICMP and UDP probes.
 */
object IcmpErrQueueParser {
    const val SO_EE_ORIGIN_LOCAL: Int = 1
    const val SO_EE_ORIGIN_ICMP: Int = 2
    const val SO_EE_ORIGIN_ICMP6: Int = 3
    const val ICMP_V4_TIME_EXCEEDED: Int = 11
    const val ICMP_V4_DEST_UNREACH: Int = 3
    const val ICMP_V4_PORT_UNREACH_CODE: Int = 3
    const val ICMP_V6_TIME_EXCEEDED: Int = 3
    const val ICMP_V6_DEST_UNREACH: Int = 1
    const val ICMP_V6_PORT_UNREACH_CODE: Int = 4

    private const val LINUX_AF_INET: Int = 2
    private const val LINUX_AF_INET6: Int = 10
    private val UDP_PROBE_MAGIC = byteArrayOf(0x4E, 0x53, 0x54, 0x52) // "NSTR"

    data class ParsedErrQueueMessage(
        val origin: Int,
        val icmpType: Int,
        val icmpCode: Int,
        val offenderIp: String?,
        val embeddedSequence: Int? = null,
    )

    fun parse(
        cmsgData: ByteArray?,
        ipFamily: IpFamily,
        byteOrder: ByteOrder = ByteOrder.nativeOrder(),
        embeddedSequence: Int? = null,
    ): ParsedErrQueueMessage? {
        if (cmsgData == null || cmsgData.size < 16) return null
        val origin = cmsgData[4].toInt() and 0xFF
        val icmpType = cmsgData[5].toInt() and 0xFF
        val icmpCode = cmsgData[6].toInt() and 0xFF

        val familyHint = if (cmsgData.size >= 18) {
            ByteBuffer.wrap(cmsgData, 16, 2).order(byteOrder).short.toInt() and 0xFFFF
        } else {
            0
        }

        val offenderIp = when {
            (origin == SO_EE_ORIGIN_ICMP || (familyHint == LINUX_AF_INET && origin != SO_EE_ORIGIN_ICMP6)) &&
                cmsgData.size >= 24 &&
                (ipFamily != IpFamily.IPV6 || familyHint == LINUX_AF_INET) -> {
                val addrBytes = cmsgData.copyOfRange(20, 24)
                runCatching { InetAddress.getByAddress(addrBytes).hostAddress }.getOrNull()
            }
            (origin == SO_EE_ORIGIN_ICMP6 || (familyHint == LINUX_AF_INET6 && origin != SO_EE_ORIGIN_ICMP)) &&
                cmsgData.size >= 40 -> {
                val addrBytes = cmsgData.copyOfRange(24, 40)
                runCatching { InetAddress.getByAddress(addrBytes).hostAddress }.getOrNull()
            }
            else -> null
        }

        return ParsedErrQueueMessage(
            origin = origin,
            icmpType = icmpType,
            icmpCode = icmpCode,
            offenderIp = offenderIp,
            embeddedSequence = embeddedSequence,
        )
    }

    /**
     * Extracts the 16-bit ICMP Echo Request sequence number from the original offending packet
     * returned by `recvmsg(..., MSG_ERRQUEUE)` in `msg_iov`.
     */
    fun extractOriginalIcmpSequence(dataBuf: ByteArray, length: Int): Int? {
        if (length < 8 || length > dataBuf.size) return null
        val b0 = dataBuf[0].toInt() and 0xFF
        // Direct ICMPv4 Echo Request (type 8) or ICMPv6 Echo Request (type 128)
        if (b0 == IcmpEchoPacketCodec.ICMPV4_ECHO_REQUEST_TYPE ||
            b0 == IcmpEchoPacketCodec.ICMPV6_ECHO_REQUEST_TYPE
        ) {
            return ((dataBuf[6].toInt() and 0xFF) shl 8) or (dataBuf[7].toInt() and 0xFF)
        }
        // Fallback: if kernel included an outer IPv4 header (version == 4, IHL >= 20)
        if ((b0 ushr 4) == 4) {
            val ihl = (b0 and 0x0F) * 4
            if (ihl >= 20 && length >= ihl + 8) {
                val innerType = dataBuf[ihl].toInt() and 0xFF
                if (innerType == IcmpEchoPacketCodec.ICMPV4_ECHO_REQUEST_TYPE) {
                    return ((dataBuf[ihl + 6].toInt() and 0xFF) shl 8) or (dataBuf[ihl + 7].toInt() and 0xFF)
                }
            }
        }
        return null
    }

    /**
     * Builds a 32-byte traceroute UDP probe payload tagged with a magic prefix, sequence, and TTL.
     */
    fun encodeUdpProbePayload(sequence: Int, ttl: Int): ByteArray {
        val buf = ByteArray(32)
        UDP_PROBE_MAGIC.copyInto(buf, destinationOffset = 0)
        buf[4] = ((sequence ushr 8) and 0xFF).toByte()
        buf[5] = (sequence and 0xFF).toByte()
        buf[6] = (ttl and 0xFF).toByte()
        buf[7] = 0x01
        for (i in 8 until buf.size) {
            buf[i] = (0x40 + (i and 0x1F)).toByte()
        }
        return buf
    }

    /**
     * Decodes the 16-bit sequence number from an offending UDP probe payload returned via `MSG_ERRQUEUE`.
     */
    fun decodeUdpProbeSequence(dataBuf: ByteArray, length: Int): Int? {
        if (length < 8 || length > dataBuf.size) return null
        if (dataBuf[0] == UDP_PROBE_MAGIC[0] &&
            dataBuf[1] == UDP_PROBE_MAGIC[1] &&
            dataBuf[2] == UDP_PROBE_MAGIC[2] &&
            dataBuf[3] == UDP_PROBE_MAGIC[3]
        ) {
            return ((dataBuf[4].toInt() and 0xFF) shl 8) or (dataBuf[5].toInt() and 0xFF)
        }
        return null
    }
}

internal interface TraceroutePosixSyscalls {
    fun ensureSupportedRuntime(sdkInt: Int): Boolean

    @Throws(Exception::class)
    fun openNonBlockingIcmpSocket(ipFamily: IpFamily): FileDescriptor

    @Throws(Exception::class)
    fun openNonBlockingUdpSocket(ipFamily: IpFamily): FileDescriptor =
        throw UnsupportedOperationException("UDP socket probing unavailable")

    @Throws(Exception::class)
    fun openNonBlockingTcpSocket(ipFamily: IpFamily): FileDescriptor =
        throw UnsupportedOperationException("TCP socket probing unavailable")

    @Throws(Exception::class)
    fun enableRecverr(fd: FileDescriptor, ipFamily: IpFamily)

    @Throws(Exception::class)
    fun setTtl(fd: FileDescriptor, ipFamily: IpFamily, ttl: Int)

    @Throws(Exception::class)
    fun connectNonBlocking(fd: FileDescriptor, address: InetAddress, port: Int): Boolean = false

    @Throws(Exception::class)
    fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress)

    @Throws(Exception::class)
    fun sendToPort(fd: FileDescriptor, packet: ByteArray, address: InetAddress, port: Int) {
        sendTo(fd, packet, address)
    }

    @Throws(Exception::class)
    fun pollEvents(fd: FileDescriptor, timeoutMs: Int): Int

    @Throws(Exception::class)
    fun pollEventsWithMask(fd: FileDescriptor, eventsMask: Int, timeoutMs: Int): Int =
        pollEvents(fd, timeoutMs)

    @Throws(Exception::class)
    fun recvEchoReply(fd: FileDescriptor, buffer: ByteArray): Int

    @Throws(Exception::class)
    fun recvErrQueue(fd: FileDescriptor, ipFamily: IpFamily): IcmpErrQueueParser.ParsedErrQueueMessage?

    fun getSocketError(fd: FileDescriptor): Int = 0

    fun closeQuietly(fd: FileDescriptor)
}

internal object SystemTraceroutePosixSyscalls : TraceroutePosixSyscalls {
    private const val LINUX_IP_TTL = 2
    private const val LINUX_IP_RECVERR = 11
    private const val LINUX_IPV6_UNICAST_HOPS = 16
    private const val LINUX_IPV6_RECVERR = 25
    private const val LINUX_MSG_PEEK = 0x02
    private const val LINUX_MSG_DONTWAIT = 0x40
    private const val LINUX_MSG_ERRQUEUE = 0x2000
    private const val LINUX_EINTR = 4
    private const val LINUX_EAGAIN = 11
    private const val LINUX_EINPROGRESS = 115

    override fun ensureSupportedRuntime(sdkInt: Int): Boolean {
        if (sdkInt < Build.VERSION_CODES.S) return false
        return OsConstants.AF_INET != 0 && OsConstants.SOCK_DGRAM != 0
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun openNonBlockingIcmpSocket(ipFamily: IpFamily): FileDescriptor {
        val domain = if (ipFamily == IpFamily.IPV6) OsConstants.AF_INET6 else OsConstants.AF_INET
        val protocol = if (ipFamily == IpFamily.IPV6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP
        return openNonBlockingSocket(domain, OsConstants.SOCK_DGRAM, protocol)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun openNonBlockingUdpSocket(ipFamily: IpFamily): FileDescriptor {
        val domain = if (ipFamily == IpFamily.IPV6) OsConstants.AF_INET6 else OsConstants.AF_INET
        return openNonBlockingSocket(domain, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun openNonBlockingTcpSocket(ipFamily: IpFamily): FileDescriptor {
        val domain = if (ipFamily == IpFamily.IPV6) OsConstants.AF_INET6 else OsConstants.AF_INET
        return openNonBlockingSocket(domain, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun openNonBlockingSocket(domain: Int, type: Int, protocol: Int): FileDescriptor {
        val fd = try {
            Os.socket(domain, type or OsConstants.SOCK_NONBLOCK, protocol)
        } catch (e: ErrnoException) {
            // Some OEM kernels reject SOCK_NONBLOCK combined into socket() type; fall back to fcntl.
            val fallback = Os.socket(domain, type, protocol)
            if (fallback != null && fallback.valid()) {
                runCatching {
                    val flags = Os.fcntlInt(fallback, OsConstants.F_GETFL, 0)
                    if ((flags and OsConstants.O_NONBLOCK) == 0) {
                        Os.fcntlInt(fallback, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
                    }
                }
            }
            fallback
        }
        if (fd == null || !fd.valid()) {
            throw UnsupportedOperationException("Os.socket returned invalid FileDescriptor")
        }
        return fd
    }

    override fun enableRecverr(fd: FileDescriptor, ipFamily: IpFamily) {
        if (ipFamily == IpFamily.IPV6) {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, LINUX_IPV6_RECVERR, 1)
        } else {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, LINUX_IP_RECVERR, 1)
        }
    }

    override fun setTtl(fd: FileDescriptor, ipFamily: IpFamily, ttl: Int) {
        val clamped = ttl.coerceIn(1, 255)
        if (ipFamily == IpFamily.IPV6) {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, LINUX_IPV6_UNICAST_HOPS, clamped)
        } else {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, LINUX_IP_TTL, clamped)
        }
    }

    override fun connectNonBlocking(fd: FileDescriptor, address: InetAddress, port: Int): Boolean {
        return try {
            Os.connect(fd, address, port)
            true
        } catch (e: ErrnoException) {
            val einprogress = OsConstants.EINPROGRESS.takeIf { it != 0 } ?: LINUX_EINPROGRESS
            val eagain = OsConstants.EAGAIN.takeIf { it != 0 } ?: LINUX_EAGAIN
            if (e.errno == einprogress || e.errno == eagain) {
                false
            } else {
                throw e
            }
        }
    }

    override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
        Os.sendto(fd, packet, 0, packet.size, 0, address, 0)
    }

    override fun sendToPort(fd: FileDescriptor, packet: ByteArray, address: InetAddress, port: Int) {
        Os.sendto(fd, packet, 0, packet.size, 0, address, port)
    }

    override fun pollEvents(fd: FileDescriptor, timeoutMs: Int): Int =
        pollEventsWithMask(fd, OsConstants.POLLIN or OsConstants.POLLERR, timeoutMs)

    override fun pollEventsWithMask(fd: FileDescriptor, eventsMask: Int, timeoutMs: Int): Int {
        val pollfd = StructPollfd().apply {
            this.fd = fd
            this.events = eventsMask.toShort()
        }
        val eintr = OsConstants.EINTR.takeIf { it != 0 } ?: LINUX_EINTR
        return try {
            val ready = Os.poll(arrayOf(pollfd), timeoutMs.coerceAtLeast(1))
            if (ready <= 0) 0 else pollfd.revents.toInt()
        } catch (e: ErrnoException) {
            if (e.errno == eintr) 0 else throw e
        }
    }

    override fun recvEchoReply(fd: FileDescriptor, buffer: ByteArray): Int {
        val eagain = OsConstants.EAGAIN.takeIf { it != 0 } ?: LINUX_EAGAIN
        val eintr = OsConstants.EINTR.takeIf { it != 0 } ?: LINUX_EINTR
        return try {
            Os.recvfrom(fd, buffer, 0, buffer.size, LINUX_MSG_DONTWAIT, null)
        } catch (e: ErrnoException) {
            if (e.errno == eagain || e.errno == eintr) -1 else throw e
        }
    }

    override fun recvErrQueue(
        fd: FileDescriptor,
        ipFamily: IpFamily,
    ): IcmpErrQueueParser.ParsedErrQueueMessage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Api31ErrQueueImpl.recvErr(fd, ipFamily)
    }

    override fun getSocketError(fd: FileDescriptor): Int {
        val eagain = OsConstants.EAGAIN.takeIf { it != 0 } ?: LINUX_EAGAIN
        val eintr = OsConstants.EINTR.takeIf { it != 0 } ?: LINUX_EINTR
        val probeBuf = ByteArray(1)
        return try {
            // Non-blocking MSG_PEEK triggers Linux sock_error(sk), atomically reading and clearing
            // pending sk_err (e.g. ECONNREFUSED, EHOSTUNREACH, EPERM) without consuming payload data.
            Os.recvfrom(fd, probeBuf, 0, 1, LINUX_MSG_PEEK or LINUX_MSG_DONTWAIT, null)
            0
        } catch (e: ErrnoException) {
            if (e.errno == eagain || e.errno == eintr) 0 else e.errno
        } catch (_: Throwable) {
            0
        }
    }

    override fun closeQuietly(fd: FileDescriptor) {
        runCatching {
            if (fd.valid()) {
                Os.close(fd)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31ErrQueueImpl {
        fun recvErr(fd: FileDescriptor, ipFamily: IpFamily): IcmpErrQueueParser.ParsedErrQueueMessage? {
            val dataBuf = ByteArray(512)
            val iov = arrayOf(ByteBuffer.wrap(dataBuf))
            val controlSeed = arrayOf(StructCmsghdr(0, 0, ByteArray(256)))
            val msgHdr = StructMsghdr(null, iov, controlSeed, 0)
            val eagain = OsConstants.EAGAIN.takeIf { it != 0 } ?: LINUX_EAGAIN
            val eintr = OsConstants.EINTR.takeIf { it != 0 } ?: LINUX_EINTR
            val bytesRead = try {
                Os.recvmsg(fd, msgHdr, LINUX_MSG_ERRQUEUE or LINUX_MSG_DONTWAIT)
            } catch (e: ErrnoException) {
                if (e.errno == eagain || e.errno == eintr) return null
                throw e
            }
            val embeddedSeq = if (bytesRead > 0) {
                IcmpErrQueueParser.extractOriginalIcmpSequence(dataBuf, bytesRead)
                    ?: IcmpErrQueueParser.decodeUdpProbeSequence(dataBuf, bytesRead)
            } else {
                null
            }
            val controls = msgHdr.msg_control ?: return null
            for (cmsg in controls) {
                val isV4Err = cmsg.cmsg_level == OsConstants.IPPROTO_IP &&
                    cmsg.cmsg_type == LINUX_IP_RECVERR
                val isV6Err = cmsg.cmsg_level == OsConstants.IPPROTO_IPV6 &&
                    cmsg.cmsg_type == LINUX_IPV6_RECVERR
                if (isV4Err || isV6Err) {
                    val parsed = IcmpErrQueueParser.parse(
                        cmsgData = cmsg.cmsg_data,
                        ipFamily = if (isV6Err) IpFamily.IPV6 else ipFamily,
                        embeddedSequence = embeddedSeq,
                    )
                    if (parsed != null) return parsed
                }
            }
            return null
        }
    }
}

class AndroidOsTracerouteKernelOps internal constructor(
    private val sdkInt: Int,
    private val syscalls: TraceroutePosixSyscalls,
    private val nanoClock: () -> Long = { System.nanoTime() },
) : TracerouteKernelOps {

    constructor(sdkInt: Int = Build.VERSION.SDK_INT) : this(
        sdkInt = sdkInt,
        syscalls = SystemTraceroutePosixSyscalls,
    )

    @Volatile
    private var lastSendNanos: Long = 0L

    override val supportsConcurrentMultiProtocol: Boolean = true

    override fun isSupported(): Boolean = syscalls.ensureSupportedRuntime(sdkInt)

    override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?): FileDescriptor {
        val fd = syscalls.openNonBlockingIcmpSocket(ipFamily)
        try {
            if (channel != null && channel.supportsFileDescriptorBind) {
                channel.bindDatagramFileDescriptor(fd)
            }
            syscalls.enableRecverr(fd, ipFamily)
            return fd
        } catch (t: Throwable) {
            syscalls.closeQuietly(fd)
            throw t
        }
    }

    override fun sendEchoWithTtl(
        fd: FileDescriptor,
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        ttl: Int,
        identifier: Int,
        sequence: Int,
    ) {
        // Clear any late-arriving ICMP error left in sk_err / MSG_ERRQUEUE from a prior hop
        // so Linux net/ipv4/ping.c does not fail the next sendto() with EHOSTUNREACH.
        drainPendingErrors(fd, ipFamily)
        syscalls.setTtl(fd, ipFamily, ttl)
        val sendNanos = nanoClock()
        lastSendNanos = sendNanos
        val packet = IcmpEchoPacketCodec.encodeEchoRequest(
            ipFamily = ipFamily,
            identifier = identifier,
            sequence = sequence,
            payloadSize = IcmpEchoPacketCodec.DEFAULT_PAYLOAD_BYTES,
            timestampNanos = sendNanos,
        )
        syscalls.sendTo(fd, packet, targetAddress)
    }

    override fun awaitReplyOrError(
        fd: FileDescriptor,
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        identifier: Int,
        expectedSequence: Int,
        timeoutMs: Int,
    ): TracerouteProbeReply {
        val startNanos = lastSendNanos.takeIf { it > 0L } ?: nanoClock()
        return awaitIcmpReplyOrError(
            fd = fd,
            targetAddress = targetAddress,
            ipFamily = ipFamily,
            expectedSequence = expectedSequence,
            startNanos = startNanos,
            timeoutMs = timeoutMs,
        )
    }

    override fun probeHop(
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        ttl: Int,
        identifier: Int,
        sequence: Int,
        timeoutMs: Int,
        channel: NetBenchSocketChannel?,
        protocol: TracerouteProbeProtocol,
        targetPort: Int,
    ): TracerouteProbeReply = when (protocol) {
        TracerouteProbeProtocol.ICMP -> probeHopIcmp(
            targetAddress = targetAddress,
            ipFamily = ipFamily,
            ttl = ttl,
            identifier = identifier,
            sequence = sequence,
            timeoutMs = timeoutMs,
            channel = channel,
        )
        TracerouteProbeProtocol.UDP -> probeHopUdp(
            targetAddress = targetAddress,
            ipFamily = ipFamily,
            ttl = ttl,
            sequence = sequence,
            timeoutMs = timeoutMs,
            channel = channel,
        )
        TracerouteProbeProtocol.TCP -> probeHopTcp(
            targetAddress = targetAddress,
            ipFamily = ipFamily,
            ttl = ttl,
            sequence = sequence,
            timeoutMs = timeoutMs,
            channel = channel,
            targetPort = targetPort,
        )
    }

    private fun probeHopIcmp(
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        ttl: Int,
        identifier: Int,
        sequence: Int,
        timeoutMs: Int,
        channel: NetBenchSocketChannel?,
    ): TracerouteProbeReply {
        val fd = openBoundSocket(ipFamily, channel)
        try {
            syscalls.setTtl(fd, ipFamily, ttl)
            val sendNanos = nanoClock()
            val packet = IcmpEchoPacketCodec.encodeEchoRequest(
                ipFamily = ipFamily,
                identifier = identifier,
                sequence = sequence,
                payloadSize = IcmpEchoPacketCodec.DEFAULT_PAYLOAD_BYTES,
                timestampNanos = sendNanos,
            )
            syscalls.sendTo(fd, packet, targetAddress)
            return awaitIcmpReplyOrError(
                fd = fd,
                targetAddress = targetAddress,
                ipFamily = ipFamily,
                expectedSequence = sequence,
                startNanos = sendNanos,
                timeoutMs = timeoutMs,
            )
        } finally {
            syscalls.closeQuietly(fd)
        }
    }

    private fun awaitIcmpReplyOrError(
        fd: FileDescriptor,
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        expectedSequence: Int,
        startNanos: Long,
        timeoutMs: Int,
    ): TracerouteProbeReply {
        val deadlineNanos = startNanos + timeoutMs.coerceAtLeast(50) * 1_000_000L
        val recvBuffer = ByteArray(512)
        val pollErrMask = OsConstants.POLLERR.takeIf { it != 0 } ?: LINUX_POLLERR
        val pollInMask = OsConstants.POLLIN.takeIf { it != 0 } ?: LINUX_POLLIN

        while (true) {
            val remainingMs = ((deadlineNanos - nanoClock()) / 1_000_000L).toInt()
            if (remainingMs <= 0) {
                return TracerouteProbeReply(
                    kind = TracerouteProbeReply.Kind.TIMEOUT,
                    responderIp = null,
                    sequence = expectedSequence,
                    rttMs = null,
                    protocol = TracerouteProbeProtocol.ICMP,
                )
            }
            val revents = syscalls.pollEvents(fd, remainingMs.coerceAtMost(50))
            if (revents == 0) continue

            if ((revents and pollErrMask) != 0) {
                val parsed = syscalls.recvErrQueue(fd, ipFamily)
                if (parsed != null) {
                    val seqMatch = parsed.embeddedSequence == null ||
                        parsed.embeddedSequence == (expectedSequence and 0xFFFF)
                    if (!seqMatch) {
                        // Stale error from a previous probe on a reused socket; keep waiting
                        continue
                    }
                    val rttMs = ((nanoClock() - startNanos).coerceAtLeast(0L)) / 1_000_000.0
                    val kind = classifyIcmpErrKind(parsed, ipFamily)
                    // Clear sk_err so any subsequent operation on a shared socket succeeds
                    syscalls.getSocketError(fd)
                    return TracerouteProbeReply(
                        kind = kind,
                        responderIp = parsed.offenderIp,
                        sequence = expectedSequence,
                        rttMs = rttMs,
                        icmpType = parsed.icmpType,
                        icmpCode = parsed.icmpCode,
                        protocol = TracerouteProbeProtocol.ICMP,
                    )
                } else {
                    // Drain SO_ERROR so POLLERR does not busy-spin until deadline
                    val soErr = syscalls.getSocketError(fd)
                    if (isFatalBindOrPermissionErrno(soErr)) {
                        throw IcmpPosixException(soErr, "ICMP socket error errno=$soErr")
                    }
                }
            }

            if ((revents and pollInMask) != 0) {
                val len = runCatching { syscalls.recvEchoReply(fd, recvBuffer) }.getOrDefault(-1)
                if (len > 0) {
                    val decoded = IcmpEchoPacketCodec.decodeEchoReply(recvBuffer, len, ipFamily)
                    if (decoded != null && decoded.sequence == (expectedSequence and 0xFFFF)) {
                        val rttMs = ((nanoClock() - startNanos).coerceAtLeast(0L)) / 1_000_000.0
                        return TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                            responderIp = targetAddress.hostAddress,
                            sequence = expectedSequence,
                            rttMs = rttMs,
                            icmpType = decoded.type,
                            icmpCode = decoded.code,
                            protocol = TracerouteProbeProtocol.ICMP,
                        )
                    }
                }
            }
        }
    }

    private fun probeHopUdp(
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        ttl: Int,
        sequence: Int,
        timeoutMs: Int,
        channel: NetBenchSocketChannel?,
    ): TracerouteProbeReply {
        val fd = syscalls.openNonBlockingUdpSocket(ipFamily)
        try {
            if (channel != null && channel.supportsFileDescriptorBind) {
                channel.bindDatagramFileDescriptor(fd)
            }
            syscalls.enableRecverr(fd, ipFamily)
            syscalls.setTtl(fd, ipFamily, ttl)
            val destPort = TracerouteKernelOps.BASE_UDP_TRACEROUTE_PORT + ((ttl * 3 + sequence) and 0x5F)
            // Connecting the per-probe UDP socket guarantees Linux matches incoming ICMP errors
            // to this socket's error queue across all Android kernel versions.
            runCatching { syscalls.connectNonBlocking(fd, targetAddress, destPort) }

            val sendNanos = nanoClock()
            val payload = IcmpErrQueueParser.encodeUdpProbePayload(sequence, ttl)
            syscalls.sendToPort(fd, payload, targetAddress, destPort)

            val deadlineNanos = sendNanos + timeoutMs.coerceAtLeast(50) * 1_000_000L
            val pollErrMask = OsConstants.POLLERR.takeIf { it != 0 } ?: LINUX_POLLERR
            val pollInMask = OsConstants.POLLIN.takeIf { it != 0 } ?: LINUX_POLLIN
            val recvBuf = ByteArray(128)

            while (true) {
                val remainingMs = ((deadlineNanos - nanoClock()) / 1_000_000L).toInt()
                if (remainingMs <= 0) {
                    return TracerouteProbeReply(
                        kind = TracerouteProbeReply.Kind.TIMEOUT,
                        responderIp = null,
                        sequence = sequence,
                        rttMs = null,
                        protocol = TracerouteProbeProtocol.UDP,
                    )
                }
                val revents = syscalls.pollEvents(fd, remainingMs.coerceAtMost(50))
                if (revents == 0) continue

                if ((revents and pollErrMask) != 0) {
                    val parsed = syscalls.recvErrQueue(fd, ipFamily)
                    if (parsed != null) {
                        val rttMs = ((nanoClock() - sendNanos).coerceAtLeast(0L)) / 1_000_000.0
                        val isPortUnreach = (ipFamily != IpFamily.IPV6 &&
                            parsed.icmpType == IcmpErrQueueParser.ICMP_V4_DEST_UNREACH &&
                            parsed.icmpCode == IcmpErrQueueParser.ICMP_V4_PORT_UNREACH_CODE) ||
                            (ipFamily == IpFamily.IPV6 &&
                                parsed.icmpType == IcmpErrQueueParser.ICMP_V6_DEST_UNREACH &&
                                parsed.icmpCode == IcmpErrQueueParser.ICMP_V6_PORT_UNREACH_CODE)
                        val isFromTarget = parsed.offenderIp != null &&
                            parsed.offenderIp.equals(targetAddress.hostAddress, ignoreCase = true)
                        val kind = when {
                            isPortUnreach || (isFromTarget && classifyIcmpErrKind(parsed, ipFamily) == TracerouteProbeReply.Kind.DEST_UNREACHABLE) ->
                                TracerouteProbeReply.Kind.ECHO_REPLY
                            else -> classifyIcmpErrKind(parsed, ipFamily)
                        }
                        return TracerouteProbeReply(
                            kind = kind,
                            responderIp = parsed.offenderIp ?: if (isPortUnreach) targetAddress.hostAddress else null,
                            sequence = sequence,
                            rttMs = rttMs,
                            icmpType = parsed.icmpType,
                            icmpCode = parsed.icmpCode,
                            protocol = TracerouteProbeProtocol.UDP,
                        )
                    } else {
                        val soErr = syscalls.getSocketError(fd)
                        val econnrefused = OsConstants.ECONNREFUSED.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_ECONNREFUSED
                        if (soErr == econnrefused) {
                            val rttMs = ((nanoClock() - sendNanos).coerceAtLeast(0L)) / 1_000_000.0
                            return TracerouteProbeReply(
                                kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                                responderIp = targetAddress.hostAddress,
                                sequence = sequence,
                                rttMs = rttMs,
                                protocol = TracerouteProbeProtocol.UDP,
                            )
                        }
                        if (isFatalBindOrPermissionErrno(soErr)) {
                            throw IcmpPosixException(soErr, "UDP socket error errno=$soErr")
                        }
                    }
                }

                if ((revents and pollInMask) != 0) {
                    val len = runCatching { syscalls.recvEchoReply(fd, recvBuf) }.getOrDefault(-1)
                    if (len >= 0) {
                        val rttMs = ((nanoClock() - sendNanos).coerceAtLeast(0L)) / 1_000_000.0
                        return TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                            responderIp = targetAddress.hostAddress,
                            sequence = sequence,
                            rttMs = rttMs,
                            protocol = TracerouteProbeProtocol.UDP,
                        )
                    }
                }
            }
        } finally {
            syscalls.closeQuietly(fd)
        }
    }

    private fun probeHopTcp(
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        ttl: Int,
        sequence: Int,
        timeoutMs: Int,
        channel: NetBenchSocketChannel?,
        targetPort: Int,
    ): TracerouteProbeReply {
        val fd = syscalls.openNonBlockingTcpSocket(ipFamily)
        try {
            if (channel != null && channel.supportsFileDescriptorBind) {
                channel.bindDatagramFileDescriptor(fd)
            }
            runCatching { syscalls.enableRecverr(fd, ipFamily) }
            syscalls.setTtl(fd, ipFamily, ttl)

            val sendNanos = nanoClock()
            val connectedNow = try {
                syscalls.connectNonBlocking(fd, targetAddress, targetPort.coerceIn(1, 65535))
            } catch (t: Throwable) {
                val errno = extractPosixErrno(t)
                val econnrefused = OsConstants.ECONNREFUSED.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_ECONNREFUSED
                if (errno == econnrefused) {
                    val rttMs = ((nanoClock() - sendNanos).coerceAtLeast(0L)) / 1_000_000.0
                    return TracerouteProbeReply(
                        kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                        responderIp = targetAddress.hostAddress,
                        sequence = sequence,
                        rttMs = rttMs,
                        protocol = TracerouteProbeProtocol.TCP,
                    )
                }
                throw t
            }
            if (connectedNow) {
                val rttMs = ((nanoClock() - sendNanos).coerceAtLeast(0L)) / 1_000_000.0
                return TracerouteProbeReply(
                    kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                    responderIp = targetAddress.hostAddress,
                    sequence = sequence,
                    rttMs = rttMs,
                    protocol = TracerouteProbeProtocol.TCP,
                )
            }

            val deadlineNanos = sendNanos + timeoutMs.coerceAtLeast(50) * 1_000_000L
            val pollOutMask = OsConstants.POLLOUT.takeIf { it != 0 } ?: LINUX_POLLOUT
            val pollErrMask = OsConstants.POLLERR.takeIf { it != 0 } ?: LINUX_POLLERR
            val pollHupMask = OsConstants.POLLHUP.takeIf { it != 0 } ?: LINUX_POLLHUP
            val econnrefused = OsConstants.ECONNREFUSED.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_ECONNREFUSED

            while (true) {
                val remainingMs = ((deadlineNanos - nanoClock()) / 1_000_000L).toInt()
                if (remainingMs <= 0) {
                    return TracerouteProbeReply(
                        kind = TracerouteProbeReply.Kind.TIMEOUT,
                        responderIp = null,
                        sequence = sequence,
                        rttMs = null,
                        protocol = TracerouteProbeProtocol.TCP,
                    )
                }
                val revents = syscalls.pollEventsWithMask(
                    fd = fd,
                    eventsMask = pollOutMask or pollErrMask,
                    timeoutMs = remainingMs.coerceAtMost(50),
                )
                if (revents == 0) continue

                val hasErrOrHup = (revents and (pollErrMask or pollHupMask)) != 0
                if ((revents and pollErrMask) != 0) {
                    val parsed = runCatching { syscalls.recvErrQueue(fd, ipFamily) }.getOrNull()
                    if (parsed != null && parsed.offenderIp != null) {
                        val rttMs = ((nanoClock() - sendNanos).coerceAtLeast(0L)) / 1_000_000.0
                        val rawKind = classifyIcmpErrKind(parsed, ipFamily)
                        val isFromTarget = parsed.offenderIp.equals(targetAddress.hostAddress, ignoreCase = true)
                        val kind = if (rawKind == TracerouteProbeReply.Kind.DEST_UNREACHABLE && isFromTarget) {
                            TracerouteProbeReply.Kind.ECHO_REPLY
                        } else {
                            rawKind
                        }
                        return TracerouteProbeReply(
                            kind = kind,
                            responderIp = parsed.offenderIp,
                            sequence = sequence,
                            rttMs = rttMs,
                            icmpType = parsed.icmpType,
                            icmpCode = parsed.icmpCode,
                            protocol = TracerouteProbeProtocol.TCP,
                        )
                    }
                }

                if (hasErrOrHup) {
                    val soErr = syscalls.getSocketError(fd)
                    val rttMs = ((nanoClock() - sendNanos).coerceAtLeast(0L)) / 1_000_000.0
                    return when {
                        soErr == econnrefused -> TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                            responderIp = targetAddress.hostAddress,
                            sequence = sequence,
                            rttMs = rttMs,
                            protocol = TracerouteProbeProtocol.TCP,
                        )
                        isFatalBindOrPermissionErrno(soErr) -> {
                            throw IcmpPosixException(soErr, "TCP socket error errno=$soErr")
                        }
                        else -> {
                            // POLLERR/POLLHUP without an errqueue offender or ECONNREFUSED must NOT
                            // be misclassified as target reached even if getSocketError() returns 0.
                            TracerouteProbeReply(
                                kind = TracerouteProbeReply.Kind.TIMEOUT,
                                responderIp = null,
                                sequence = sequence,
                                rttMs = null,
                                protocol = TracerouteProbeProtocol.TCP,
                            )
                        }
                    }
                }

                if ((revents and pollOutMask) != 0) {
                    val soErr = syscalls.getSocketError(fd)
                    val rttMs = ((nanoClock() - sendNanos).coerceAtLeast(0L)) / 1_000_000.0
                    return when {
                        soErr == 0 || soErr == econnrefused -> TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                            responderIp = targetAddress.hostAddress,
                            sequence = sequence,
                            rttMs = rttMs,
                            protocol = TracerouteProbeProtocol.TCP,
                        )
                        isFatalBindOrPermissionErrno(soErr) -> {
                            throw IcmpPosixException(soErr, "TCP socket error errno=$soErr")
                        }
                        else -> TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.TIMEOUT,
                            responderIp = null,
                            sequence = sequence,
                            rttMs = null,
                            protocol = TracerouteProbeProtocol.TCP,
                        )
                    }
                }
            }
        } finally {
            syscalls.closeQuietly(fd)
        }
    }

    private fun drainPendingErrors(fd: FileDescriptor, ipFamily: IpFamily) {
        repeat(8) {
            val drained = runCatching { syscalls.recvErrQueue(fd, ipFamily) }.getOrNull() ?: return@repeat
            if (drained.offenderIp == null && drained.origin == 0) return@repeat
        }
        runCatching { syscalls.getSocketError(fd) }
    }

    private fun classifyIcmpErrKind(
        parsed: IcmpErrQueueParser.ParsedErrQueueMessage,
        ipFamily: IpFamily,
    ): TracerouteProbeReply.Kind = when {
        ipFamily != IpFamily.IPV6 && parsed.icmpType == IcmpErrQueueParser.ICMP_V4_TIME_EXCEEDED ->
            TracerouteProbeReply.Kind.TIME_EXCEEDED
        ipFamily == IpFamily.IPV6 && parsed.icmpType == IcmpErrQueueParser.ICMP_V6_TIME_EXCEEDED ->
            TracerouteProbeReply.Kind.TIME_EXCEEDED
        ipFamily != IpFamily.IPV6 && parsed.icmpType == IcmpErrQueueParser.ICMP_V4_DEST_UNREACH ->
            TracerouteProbeReply.Kind.DEST_UNREACHABLE
        ipFamily == IpFamily.IPV6 && parsed.icmpType == IcmpErrQueueParser.ICMP_V6_DEST_UNREACH ->
            TracerouteProbeReply.Kind.DEST_UNREACHABLE
        else -> TracerouteProbeReply.Kind.TIME_EXCEEDED
    }

    private fun isFatalBindOrPermissionErrno(errno: Int): Boolean {
        if (errno == 0) return false
        val eperm = OsConstants.EPERM.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_EPERM
        val eacces = OsConstants.EACCES.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_EACCES
        return errno == eperm || errno == eacces
    }

    override fun closeQuietly(fd: FileDescriptor) {
        syscalls.closeQuietly(fd)
    }

    companion object {
        private const val LINUX_POLLIN = 0x0001
        private const val LINUX_POLLOUT = 0x0004
        private const val LINUX_POLLERR = 0x0008
        private const val LINUX_POLLHUP = 0x0010

        internal fun isLockdownBindFailure(
            throwable: Throwable,
            isVpnActive: Boolean,
            hasDirectCandidate: Boolean,
        ): Boolean = NetBenchNetworkSelector.classifyDirectBindFailure(
            throwable = throwable,
            isVpnActive = isVpnActive,
            hasDirectCandidate = hasDirectCandidate,
        ) == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED
    }
}
