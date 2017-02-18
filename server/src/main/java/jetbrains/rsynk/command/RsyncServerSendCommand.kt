package jetbrains.rsynk.command

import jetbrains.rsynk.command.data.ProtocolVersionAndFlags
import jetbrains.rsynk.exitvalues.UnsupportedProtocolException
import jetbrains.rsynk.io.ReadingIO
import jetbrains.rsynk.io.SynchronousReadingIO
import jetbrains.rsynk.io.SynchronousWritingIO
import jetbrains.rsynk.io.WritingIO
import jetbrains.rsynk.protocol.CompatFlag
import jetbrains.rsynk.protocol.RsyncConstants
import jetbrains.rsynk.protocol.decode
import jetbrains.rsynk.protocol.encode
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream

class RsyncServerSendCommand(private val serverCompatFlags: Set<CompatFlag>) : RsyncCommand {

  override val args: List<String> = listOf("rsync", "--server", "--sender")

  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * Perform negotiation and send requested file.
   * The behaviour is identical to {@code $rsync --server --sender}
   * command execution
   *
   * Protocol phases enumerated and phases documented in protocol.md
   * */
  override fun execute(args: List<String>, input: InputStream, output: OutputStream, error: OutputStream) {
    val inputIO = SynchronousReadingIO(input)
    val outputIO = SynchronousWritingIO(output)
    val errorIO = SynchronousWritingIO(error)
    /* 1 */
    val versionAndFlags = setupProtocol(inputIO, outputIO)
    /* 2 */

  }


  /**
   * Identical to original rsync code.
   * Writes server protocol version
   * and reads protocol client's version.
   * Writes server's so-called compat-flags.
   * Receives and parses clients compat-flags.
   *
   * @throws {@code UnsupportedProtocolException} if client's protocol version
   * either too old or too modern
   *
   * @return  protocol version and compat-flags sent by client
   */
  private fun setupProtocol(input: ReadingIO, output: WritingIO): ProtocolVersionAndFlags {
    /* must write version in first byte and keep the rest 3 zeros */
    val serverVersion = byteArrayOf(RsyncConstants.protocolVersion.toByte(), 0, 0, 0)
    output.writeBytes(serverVersion, 0, 4)

    /* same for the reading: first byte is version, rest are zeros */
    val clientVersionResponse = input.readBytes(4)
    if (clientVersionResponse[1] != 0.toByte() || clientVersionResponse[2] != 0.toByte() || clientVersionResponse[3] != 0.toByte()) {
      log.error("Wrong assumption was made: at least one of last 3 elements of 4 client version buffer is not null: " +
              clientVersionResponse.joinToString())
    }

    val clientProtocolVersion = clientVersionResponse[0]
    if (clientProtocolVersion < RsyncConstants.clientProtocolVersionMin) {
      throw UnsupportedProtocolException("Client protocol version must be at least ${RsyncConstants.clientProtocolVersionMin}")
    }
    if (clientProtocolVersion > RsyncConstants.clientProtocolVersionMax) {
      throw UnsupportedProtocolException("Client protocol version must be no more than ${RsyncConstants.clientProtocolVersionMax}")
    }

    /* write server compat flags*/
    val serverCompatFlags = serverCompatFlags.encode()
    output.writeBytes(byteArrayOf(serverCompatFlags))

    /* read clients compat flags client decided to use */
    val compatFlagsResponse = input.readBytes(1)[0]
    val negotiatedCompatFlags = compatFlagsResponse.decode()

    return ProtocolVersionAndFlags(clientProtocolVersion, negotiatedCompatFlags)
  }
}