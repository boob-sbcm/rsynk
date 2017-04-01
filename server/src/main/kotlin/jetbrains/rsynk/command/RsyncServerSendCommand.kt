package jetbrains.rsynk.command

import jetbrains.rsynk.exitvalues.NotSupportedException
import jetbrains.rsynk.exitvalues.UnsupportedProtocolException
import jetbrains.rsynk.extensions.MAX_VALUE_UNSIGNED
import jetbrains.rsynk.extensions.littleEndianToInt
import jetbrains.rsynk.extensions.toLittleEndianBytes
import jetbrains.rsynk.files.*
import jetbrains.rsynk.flags.TransmitFlags
import jetbrains.rsynk.flags.encode
import jetbrains.rsynk.io.ReadingIO
import jetbrains.rsynk.io.VarintEncoder
import jetbrains.rsynk.io.WritingIO
import jetbrains.rsynk.options.Option
import jetbrains.rsynk.options.RequestOptions
import jetbrains.rsynk.protocol.RsyncServerStaticConfiguration
import mu.KLogging
import java.nio.ByteBuffer
import java.util.*


private class PreviousFileSentFileInfoCache {
    //TODO: make it immutable
    var mode: Int? = null
    var user: User? = null
    var group: Group? = null
    var lastModified: Long? = null
    var pathBytes: ByteArray = byteArrayOf()
    val sentUserNames = HashSet<User>()
    val sendGroupNames = HashSet<Group>()
}


class RsyncServerSendCommand(private val fileInfoReader: FileInfoReader) : RsyncCommand {

    companion object : KLogging()

    /**
     * Perform negotiation and send requested file.
     * The behaviour is identical to {@code $rsync --server --sender}
     * command execution
     *
     * Protocol phases enumerated and phases documented in protocol.md
     * */
    override fun execute(requestData: RequestData,
                         input: ReadingIO,
                         output: WritingIO,
                         error: WritingIO) {
        exchangeProtocolVersions(input, output)

        writeCompatFlags(output)

        writeChecksumSeed(requestData.checksumSeed, output)

        val filter = receiveFilterList(input)
        sendFileList(requestData, filter, output)
    }

    /**
     * Writes server protocol version
     * and reads protocol client's version.
     *
     * @throws {@code UnsupportedProtocolException} if client's protocol version
     * either too old or too modern
     */
    private fun exchangeProtocolVersions(input: ReadingIO, output: WritingIO) {
        output.writeBytes(RsyncServerStaticConfiguration.serverProtocolVersion.toLittleEndianBytes())
        val clientProtocolVersion = input.readBytes(4).littleEndianToInt()
        if (clientProtocolVersion < RsyncServerStaticConfiguration.clientProtocolVersionMin) {
            throw UnsupportedProtocolException("Client protocol version must be at least " +
                    RsyncServerStaticConfiguration.clientProtocolVersionMin)
        }
        if (clientProtocolVersion > RsyncServerStaticConfiguration.clientProtocolVersionMax) {
            throw UnsupportedProtocolException("Client protocol version must be no more than " +
                    RsyncServerStaticConfiguration.clientProtocolVersionMax)
        }
    }

    /**
     * Writes server's compat flags.
     */
    private fun writeCompatFlags(output: WritingIO) {
        val serverCompatFlags = RsyncServerStaticConfiguration.serverCompatFlags.encode()
        output.writeBytes(byteArrayOf(serverCompatFlags))
    }

    /**
     * Writes rolling checksum seed.
     * */
    private fun writeChecksumSeed(checksumSeed: Int, output: WritingIO) {
        output.writeBytes(checksumSeed.toLittleEndianBytes())
    }


    /**
     * Receives filter list
     * */
    private fun receiveFilterList(input: ReadingIO): FilterList {

        var len = input.readBytes(4).littleEndianToInt()

        /* It's not clear why client writes those 4 bytes.
         * Rsync uses it's 'safe_read' int early communication stages
         * which deals with circular buffer. It's probably data remained
         * in buffer. Ignore it unless we figure out the byte is missing. */
        if (len > 1024 * 5) {
            len = input.readBytes(4).littleEndianToInt()
        }

        while (len != 0) {
            throw NotSupportedException("Filter list is not supported")
            /*
            //TODO: receive & parse filter list
            //http://blog.mudflatsoftware.com/blog/2012/10/31/tricks-with-rsync-filter-rules/
            val bytes = input.readBytes(len).littleEndianToInt()
            len = input.readBytes(4).littleEndianToInt()
            */
        }
        return FilterList()
    }

    private fun sendFileList(data: RequestData, filterList: FilterList/*TODO: filter files*/, output: WritingIO) {
        if (data.filePaths.size != 1) {
            throw NotSupportedException("Multiple files requests not implemented yet")
        }

        val paths = listOf(FileResolver.resolve(data.filePaths.single()))

        val fileList = FileList(data.options.directoryMode is Option.FileSelection.TransferDirectoriesRecurse)
        val initialBlock = fileList.addFileBlock(null, paths.map { path -> fileInfoReader.getFileInfo(path) })

        val cache = PreviousFileSentFileInfoCache()
        initialBlock.files.forEach { ndx, file ->
            sendFileInfo(file, cache, data.options, output)
        }
    }

    private fun sendFileInfo(f: FileInfo, cache: PreviousFileSentFileInfoCache, options: RequestOptions, output: WritingIO) {
        var encodedAttributes = if (f.isDirectory) 1 else 0

        if (f.isBlockDevice || f.isCharacterDevice || f.isSocket || f.isFIFO) {
            // TODO set or discard TransmitFlags.SameRdevMajor
        }

        if (f.mode == cache.mode) {
            encodedAttributes = encodedAttributes or TransmitFlags.SameMode.value
        } else {
            cache.mode = f.mode
        }

        if (options.preserveUser && f.user == cache.user) {
            encodedAttributes = encodedAttributes or TransmitFlags.SameUserId.value
        } else {
            cache.user = f.user
            if (!f.user.isRoot && !options.numericIds) {
                if (options.directoryMode is Option.FileSelection.TransferDirectoriesRecurse && f.user in cache.sentUserNames) {
                    encodedAttributes = encodedAttributes or TransmitFlags.UserNameFollows.value
                }
                cache.sentUserNames.add(f.user)
            }
        }

        if (options.preserveGroup && f.group == cache.group) {
            encodedAttributes = encodedAttributes or TransmitFlags.SameGroupId.value
        } else {
            cache.group = f.group
            if (!f.group.isRoot && !options.numericIds) {
                if (options.directoryMode is Option.FileSelection.TransferDirectoriesRecurse && f.group in cache.sendGroupNames) {
                    encodedAttributes = encodedAttributes or TransmitFlags.GroupNameFollows.value
                }
            }
        }

        if (f.lastModified == cache.lastModified) {
            encodedAttributes = encodedAttributes or TransmitFlags.SameLastModifiedTime.value
        } else {
            cache.lastModified = f.lastModified
        }

        val pathBytes = f.path.toUri().path.toByteArray()
        val commonPrefixLength = (pathBytes zip cache.pathBytes).takeWhile { it.first == it.second }.size
        cache.pathBytes = pathBytes
        val suffix = Arrays.copyOfRange(pathBytes, commonPrefixLength, pathBytes.size)

        if (commonPrefixLength > 0) {
            encodedAttributes = encodedAttributes or TransmitFlags.SameName.value
        }
        if (suffix.size > Byte.MAX_VALUE_UNSIGNED) {
            encodedAttributes = encodedAttributes or TransmitFlags.SameLongName.value
        }
        if (encodedAttributes == 0 && !f.isDirectory) {
            encodedAttributes = encodedAttributes or TransmitFlags.TopDirectory.value
        }

        if (encodedAttributes == 0 || encodedAttributes and 0xFF00 != 0) {
            encodedAttributes = encodedAttributes and TransmitFlags.ExtendedFlags.value
            output.writeChar(encodedAttributes.toChar())
        } else {
            output.writeByte(encodedAttributes.toByte())
        }

        if (encodedAttributes and TransmitFlags.SameName.value != 0) {
            output.writeByte(Math.min(commonPrefixLength, Byte.MAX_VALUE_UNSIGNED).toByte())
        }

        if (encodedAttributes and TransmitFlags.SameLongName.value != 0) {
            output.writeBytes(VarintEncoder.longToBytes(suffix.size.toLong(), 1))
        } else {
            output.writeByte(suffix.size.toByte())
        }
        output.writeBytes(ByteBuffer.wrap(suffix))

        output.writeBytes(VarintEncoder.longToBytes(f.size, 3))

        if (encodedAttributes and TransmitFlags.SameLastModifiedTime.value == 0) {
            output.writeBytes(VarintEncoder.longToBytes(f.lastModified, 4))
        }

        if (encodedAttributes and TransmitFlags.SameMode.value == 0) {
            output.writeInt(f.mode)
        }

        if (options.preserveUser && encodedAttributes and TransmitFlags.SameUserId.value == 0) {
            output.writeBytes(VarintEncoder.longToBytes(f.user.uid.toLong(), 1))

            if (encodedAttributes and TransmitFlags.UserNameFollows.value != 0) {
                val buf = ByteBuffer.wrap(f.user.name.toByteArray())
                output.writeByte(buf.remaining().toByte())
                output.writeBytes(buf)
            }
        }

        if (options.preserveGroup && encodedAttributes and TransmitFlags.SameGroupId.value == 0) {
            output.writeBytes(VarintEncoder.longToBytes(f.group.gid.toLong(), 1))

            if (encodedAttributes and TransmitFlags.GroupNameFollows.value != 0) {
                val buf = ByteBuffer.wrap(f.group.name.toByteArray())
                output.writeByte(buf.remaining().toByte())
                output.writeBytes(buf)
            }
        }

        if (options.preserveDevices || options.preserveSpecials) {
            //TODO send device info if this is a device or special
        } else if (options.preserveLinks) {
            //TODO send target if this is a symlink
        }
    }

    private fun write_varint(value: Int, output: WritingIO) {
        write_var_number(value.toLittleEndianBytes(), 1, output)
    }

    private fun write_varlong(value: Long, minBytes: Int, output: WritingIO) {
        write_var_number(value.toLittleEndianBytes(), minBytes, output)
    }

    private fun write_var_number(_bytes: ByteArray, minBytes: Int, output: WritingIO) {
        var cnt = _bytes.size
        val bytes = _bytes + byteArrayOf(0)
        while (cnt > minBytes && bytes[cnt] == 0.toByte()) {
            cnt--
        }
        val bit = 1.shl(7 - cnt + minBytes)
        if (bytes[cnt] >= bit) {
            cnt++
            bytes[0] = (bit - 1).inv().toByte()
        } else if (cnt > 1) {
            bytes[0] = bytes[cnt].toInt().or((bit * 2 - 1).inv()).toByte()
        } else {
            bytes[0] = bytes[cnt]
        }
        output.writeBytes(bytes, 0, cnt)
    }
}
