package io.iohk.codecs.nio
import java.nio.ByteBuffer

import org.mockito.Mockito.{atLeastOnce, verify, verifyZeroInteractions}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalacheck.Gen._
import org.scalatest.FlatSpec
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._
import org.scalatest.Matchers._
import io.iohk.codecs.nio.auto._
import io.iohk.codecs.nio.test.utils.CodecTestingHelpers

import scala.util.Random

class StreamCodecsSpec extends FlatSpec with CodecTestingHelpers {

  object UserCode {
    case class A(i: Int, s: String)
    case class B(s: String)
    case class C(b: Boolean)
  }

  import UserCode._

  private val genA: Gen[A] = for {
    i <- arbitrary[Int]
    s <- arbitrary[String]
  } yield A(i, s)

  private val genB: Gen[B] = arbitrary[String].map(B)

  private val genC: Gen[C] = arbitrary[Boolean].map(C)

  private val genNetworkMess: Gen[List[Any]] = for {
    as <- listOf(genA)
    bs <- listOf(genB)
    cs <- listOf(genC)
  } yield Random.shuffle(as ::: bs ::: cs)

  private case class TestData(
      address: String,
      asBsCs: List[Any],
      netBuffer: ByteBuffer,
      as: Seq[A],
      bs: Seq[B],
      cs: Seq[C],
      aHandler: (String, A) => Unit,
      bHandler: (String, B) => Unit,
      cHandler: (String, C) => Unit,
      aDecoderFn: MessageApplication[String],
      bDecoderFn: MessageApplication[String],
      cDecoderFn: MessageApplication[String]
  )

  private val genTestData: Gen[TestData] = for {
    sourceAddress <- alphaStr
    asBsAndCs <- genNetworkMess
  } yield {
    val buffers = asBsAndCs.collect({
      case a: A => NioEncoder[A].encode(a)
      case b: B => NioEncoder[B].encode(b)
      case c: C => NioEncoder[C].encode(c)
    })
    val netBuffer = concatenate(buffers) // provides a random mix of As, Bs plus redundant C's as though 'on the wire'
    val as: Seq[A] = asBsAndCs.collect({ case a: A => a })
    val bs: Seq[B] = asBsAndCs.collect({ case b: B => b })
    val cs: Seq[C] = asBsAndCs.collect({ case c: C => c })

    val aHandler = mock[(String, A) => Unit]
    val bHandler = mock[(String, B) => Unit]
    val cHandler = mock[(String, C) => Unit]

    val aCodecFn = strictMessageApplication(NioCodec[A], aHandler)
    val bCodecFn = strictMessageApplication(NioCodec[B], bHandler)
    val cCodecFn = strictMessageApplication(NioCodec[C], cHandler)

    TestData(
      sourceAddress,
      asBsAndCs,
      netBuffer,
      as,
      bs,
      cs,
      aHandler,
      bHandler,
      cHandler,
      aCodecFn,
      bCodecFn,
      cCodecFn
    )
  }

  behavior of "StreamCodec"

  it should "decode a network message and apply handlers strictly" in {
    forAll(genTestData) { testData =>
      import testData._

      decodeStream(
        address,
        netBuffer,
        List(
          strictMessageApplication(NioCodec[A], aHandler),
          strictMessageApplication(NioCodec[B], bHandler),
          strictMessageApplication(NioCodec[C], cHandler)
        )
      )

      as.foreach(a => verify(aHandler, atLeastOnce()).apply(address, a))
      bs.foreach(b => verify(bHandler, atLeastOnce()).apply(address, b))
      cs.foreach(c => verify(cHandler, atLeastOnce()).apply(address, c))
    }
  }

  it should "decode a network message and apply handlers lazily" in {
    forAll(genTestData) { testData =>
      import testData._

      val decodeResult = decodeStream(
        address,
        netBuffer,
        List(
          lazyMessageApplication(NioCodec[A], aHandler),
          lazyMessageApplication(NioCodec[B], bHandler),
          lazyMessageApplication(NioCodec[C], cHandler)
        )
      )

      verifyZeroInteractions(aHandler)
      verifyZeroInteractions(bHandler)
      verifyZeroInteractions(cHandler)

      decodeResult.foreach((f: ApplicableMessage) => f.apply())

      as.foreach(a => verify(aHandler, atLeastOnce()).apply(address, a))
      bs.foreach(b => verify(bHandler, atLeastOnce()).apply(address, b))
      cs.foreach(c => verify(cHandler, atLeastOnce()).apply(address, c))
    }
  }

  it should "not choke on empty buffers" in {
    forAll(genTestData) { testData =>
      import testData._
      decodeStream(
        address,
        ByteBuffer.allocate(0),
        List(aDecoderFn, bDecoderFn, cDecoderFn)
      )

      verifyZeroInteractions(aHandler)
      verifyZeroInteractions(bHandler)
      verifyZeroInteractions(cHandler)
    }
  }

  it should "not choke on empty decoder chains" in {
    forAll(genTestData) { testData =>
      import testData._
      decodeStream(address, netBuffer, List())

      (netBuffer: java.nio.Buffer).position() shouldBe 0
    }
  }
}
