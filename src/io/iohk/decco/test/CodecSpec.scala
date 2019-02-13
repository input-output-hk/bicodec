package io.iohk.decco

import java.nio.ByteBuffer

import io.iohk.decco.Codec.heapCodec
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.decco.auto._
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.EitherValues._
import org.mockito.Mockito.{never, verify}
import org.mockito.ArgumentMatchers.any

class CodecSpec extends FlatSpec {

  behavior of "Codecs"

  they should "encode/decode a byte" in {
    val codec = heapCodec[Byte]
    codec.decode(codec.encode(0)) shouldBe Right(0)
  }

  they should "encode/decode a String" in {
    val codec = heapCodec[String]
    val bytes = codec.encode("string")
    codec.decode(bytes) shouldBe Right("string")
  }

  they should "reject incorrectly typed buffers with the correct error" in {
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode("a message")
    bytes.put(7, 0) // corrupt the header's type field

    codec.decode(bytes).left.value shouldBe DecodeFailure.BodyWrongType
  }

  they should "reject incorrectly size buffers with the correct error" in {
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode("a message")
    val truncatedBytes: ByteBuffer = truncateBody(bytes)

    codec.decode(truncatedBytes).left.value shouldBe DecodeFailure.BodyTooShort
  }

  they should "reject improperly formatted headers with the correct error" in {
    heapCodec[String].decode(ByteBuffer.allocate(0)).left.value shouldBe DecodeFailure.HeaderWrongFormat
  }

  case class A(s: String)

  case class Wrap[T](t: T)

  they should "rehydrate type information from a buffer" in {

    val message = Wrap(A("message"))
    val messageCodec = heapCodec[Wrap[A]]
    val buffer: ByteBuffer = messageCodec.encode(message)
    val expectedPf = mock[PartialCodec[Wrap[A]]]
    val unexpectedPf = mock[PartialCodec[String]]
    val availableCodecs = Map[String, (Int, ByteBuffer) => Unit](
      PartialCodec[String].typeCode -> messageWrapper(unexpectedPf),
      PartialCodec[Wrap[A]].typeCode -> messageWrapper(expectedPf)
    )

    Codec.decodeFrame(availableCodecs, 0, buffer)

    verify(expectedPf).decode(258, buffer)
    verify(unexpectedPf, never()).decode(any(), any())
  }

  they should "not allow TypeTag implicits to propagate everywhere" in {

    def functionInTheNetwork[T: PartialCodec](t: T): T = {
      val framePf: PartialCodec[Wrap[T]] = PartialCodec[Wrap[T]]
      val frameCodec = heapCodec(framePf)

      val arr = frameCodec.encode(Wrap(t))

      val maybeRestoredFrame = frameCodec.decode(arr)

      maybeRestoredFrame.right.value.t
    }

    functionInTheNetwork(A("string")) shouldBe A("string")
  }

  they should "support the recovery of backing arrays with heap codec" in {
    val codec = heapCodec[String]
    val bytes = codec.encode("string")

    val backingArray: Array[Byte] = bytes.array()

    codec.decode(ByteBuffer.wrap(backingArray)) shouldBe Right("string")
  }

  private def truncateBody(bytes: ByteBuffer): ByteBuffer = {
    val (bodySize, bodyType) = Codec.headerCodec.decode(0, bytes).right.value.decoded
    val headerSize = Codec.headerCodec.size((bodySize, bodyType))
    val truncatedBytes = new Array[Byte](headerSize) // just the header size
    Array.copy(bytes.array(), 0, truncatedBytes, 0, headerSize)
    ByteBuffer.wrap(truncatedBytes)
  }

  private def messageWrapper[T](pf: PartialCodec[T])(start: Int, source: ByteBuffer): Unit =
    pf.decode(start, source)
}
