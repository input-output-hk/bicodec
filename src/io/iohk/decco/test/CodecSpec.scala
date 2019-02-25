package io.iohk.decco

import java.nio.ByteBuffer
import java.util.UUID

import io.iohk.decco.Codec.heapCodec
import io.iohk.decco.DecodeFailure.BodyWrongType
import io.iohk.decco.TypeCode.genTypeCode
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.decco.auto._
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.EitherValues._
import org.mockito.Mockito.{never, verify}
import org.mockito.ArgumentMatchers.{any, anyInt, eq => meq}
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._

class CodecSpec extends FlatSpec {

  behavior of "Codecs"

  case class A(s: String, i: Int, l: List[String], u: UUID, f: Float)
  case class B(s: String, i: Int, l: List[String], u: UUID, f: Float)
  case class Wrap[T](t: T)

  sealed trait X[T, U]
  case class Y[T, U](t: T, u: U) extends X[T, U]
  case class Z[T, U]() extends X[T, U]

  implicit val arbitraryA: Arbitrary[A] = Arbitrary(
    for {
      s <- arbitrary[String]
      i <- arbitrary[Int]
      l <- arbitrary[List[String]]
      u <- arbitrary[UUID]
      f <- arbitrary[Float]
    } yield A(s, i, l, u, f)
  )

  implicit val arbitraryWrap: Arbitrary[Wrap[A]] = Arbitrary(arbitraryA.arbitrary.map(a => Wrap(a)))

  they should "work for fixed width types" in {
    codecTest(heapCodec[Int])
  }

  they should "work for variable width types" in {
    codecTest(heapCodec[String])
  }

  they should "work for collection types" in {
    codecTest(heapCodec[List[String]])
  }

  they should "work for user types" in {
    codecTest(heapCodec[A])
  }

  they should "rehydrate type information from a buffer" in {
    forAll { message: Wrap[A] =>
      val messageCodec = heapCodec[Wrap[A]]
      val buffer: ByteBuffer = messageCodec.encode(message)
      val expectedPf = mock[PartialCodec[Wrap[A]]]
      val unexpectedPf = mock[PartialCodec[String]]
      val availableCodecs = Map[String, (Int, ByteBuffer) => Unit](
        PartialCodec[String].typeCode.id -> messageWrapper(unexpectedPf),
        PartialCodec[Wrap[A]].typeCode.id -> messageWrapper(expectedPf)
      )

      Codec.decodeFrame(availableCodecs, 0, buffer)

      verify(expectedPf).decode(anyInt(), meq(buffer))
      verify(unexpectedPf, never()).decode(any(), any())
    }
  }

  they should "not allow TypeTag implicits to propagate everywhere for case classes" in {

    def functionInTheNetwork[T](t: T)(implicit ev: PartialCodec[T]): T = {
      implicit val wtt: TypeCode[Wrap[T]] = genTypeCode[Wrap, T]
      val framePf: PartialCodec[Wrap[T]] = PartialCodec[Wrap[T]]
      val frameCodec = heapCodec(framePf)

      val arr = frameCodec.encode(Wrap(t))

      val maybeRestoredFrame = frameCodec.decode(arr)

      maybeRestoredFrame.right.value.t
    }

    val a = A("string", 1, List(), UUID.randomUUID(), 1.1f)
    functionInTheNetwork(a) shouldBe a
  }

  they should "not allow TypeTag implicits to propagate everywhere for sealed traits" in {

    def functionInTheNetwork[T, U](
        t: T,
        u: U
    )(implicit tc: PartialCodec[T], uc: PartialCodec[U]): (X[T, U], X[T, U]) = {
      implicit val xtt: TypeCode[X[T, U]] = genTypeCode[X, T, U]
      implicit val ytt: TypeCode[Y[T, U]] = genTypeCode[Y, T, U]
      implicit val ztt: TypeCode[Z[T, U]] = genTypeCode[Z, T, U]

      val framePf: PartialCodec[X[T, U]] = PartialCodec[X[T, U]]

      val frameCodec = heapCodec(framePf)

      val yArr = frameCodec.encode(Y(t, u))
      val zArr = frameCodec.encode(Z())

      val maybeYFrame: Either[DecodeFailure, X[T, U]] = frameCodec.decode(yArr)
      val maybeZFrame: Either[DecodeFailure, X[T, U]] = frameCodec.decode(zArr)

      (maybeYFrame.right.value, maybeZFrame.right.value)
    }

    val a = "a"
    val b = 'b'

    functionInTheNetwork(a, b) shouldBe (Y(a, b), Z())
  }

  they should "support the recovery of backing arrays with heap codec" in {
    val codec = heapCodec[String]
    val bytes = codec.encode("string")

    val backingArray: Array[Byte] = bytes.array()

    codec.decode(ByteBuffer.wrap(backingArray)) shouldBe Right("string")
  }

  they should "have distinct type codes for structurally equivalent types" in {
    val aCodec = heapCodec[A]
    val bCodec = heapCodec[B]
    val a = A("a", 1, List("a"), UUID.randomUUID(), 3.14f)

    val aBuffer = aCodec.encode(a)

    bCodec.decode(aBuffer).left.value shouldBe BodyWrongType(
      expectedType = "CodecSpec.this.B",
      encounteredType = "CodecSpec.this.A"
    )
  }

  private def codecTest[T](codec: Codec[T])(implicit ev: Arbitrary[T]): Unit = {
    encodeDecodeTest(codec)
    corruptTypeHeaderTest(codec)
    truncatedBodyTest(codec)
    emptyBufferTest(codec)
  }

  private def encodeDecodeTest[T](codec: Codec[T])(implicit ev: Arbitrary[T]): Unit = {
    forAll { t: T =>
      codec.decode(codec.encode(t)) shouldBe Right(t)
    }
  }

  private def corruptTypeHeaderTest[T](codec: Codec[T])(implicit ev: Arbitrary[T]): Unit = {
    forAll { t: T =>
      val bytes: ByteBuffer = codec.encode(t)
      bytes.put(7, 0) // corrupt the header's type field
      codec.decode(bytes).left.value shouldBe a[DecodeFailure.BodyWrongType]
    }
  }

  private def truncatedBodyTest[T](codec: Codec[T])(implicit ev: Arbitrary[T]): Unit = {
    forAll { t: T =>
      val bytes: ByteBuffer = codec.encode(t)
      val truncatedBytes: ByteBuffer = truncateBody(bytes)
      codec.decode(truncatedBytes).left.value shouldBe a[DecodeFailure.BodyTooShort]
    }
  }

  private def emptyBufferTest[T](codec: Codec[T])(implicit ev: Arbitrary[T]): Unit = {
    codec.decode(ByteBuffer.allocate(0)).left.value shouldBe DecodeFailure.HeaderWrongFormat
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
