package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.Musig2.{IndividualNonce, LocalNonce}
import fr.acinq.bitcoin.scalacompat._
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector
import scala.jdk.CollectionConverters._

class Musig2DebugSpec extends AnyFunSuite {
  test("debug musig2 verification failure") {
    // Data from log 08:30:53
    val localPubKey = PublicKey(ByteVector.fromValidHex("02bfd94683279901f7d3b1c78c7b1a78f014e949348238b67af8e39ea626e71b26"))
    val remotePubKey = PublicKey(ByteVector.fromValidHex("039689bbf642dc281c49a1f59e731f96441899411e4366a6b91c23f36a3d74f74d"))
    
    val localNonce = IndividualNonce(ByteVector.fromValidHex("035554aeea02dd24442241575c24ebac839b9a8dddd9618672bf5b6606cf9e598703305540821cd0f61e85d5b2da8dff24c32891ca6b697c102e71261a381267f3b0"))
    val remoteNonce = IndividualNonce(ByteVector.fromValidHex("02a5a3fcae967457cca63c40eb8c7a5cdbf0c147bbb8204c2744b4c899871e3b4a03d066666cc694193ab79c0e797f3e2551033ecb8dd88f7fd23fb09c13bcc49353"))
    
    val partialSig = ByteVector32.fromValidHex("ee491af6412e66da8f7df1ed015c833f93438eb2f03c5e0557bae7961d3133e7")
    val sighash = ByteVector32.fromValidHex("776b874cd13c7fc35d67cea36e5a6fd0adb8472ad588109107ce215fb2acf262")
    
    val sortedPubkeys = Scripts.sort(Seq(localPubKey, remotePubKey))
    val sortedNonces = Scripts.sortNonces(Seq(localPubKey -> localNonce, remotePubKey -> remoteNonce))
    
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    
    val (aggregatePubKey, keyAggCache) = fr.acinq.bitcoin.crypto.musig2.KeyAggCache.create(sortedPubkeys.map(scala2kmp).asJava)
    val tweak = aggregatePubKey.tweak(fr.acinq.bitcoin.Crypto.TaprootTweak.KeyPathTweak)
    val tweaked = keyAggCache.tweak(tweak, true).right.get
    
    val aggregateNonce = fr.acinq.bitcoin.crypto.musig2.IndividualNonce.aggregate(sortedNonces.map(n => new fr.acinq.bitcoin.crypto.musig2.IndividualNonce(n.data.toArray)).asJava).right.get
    val session = fr.acinq.bitcoin.crypto.musig2.Session.create(aggregateNonce, scala2kmp(sighash), tweaked.first)
    
    val result = session.verify(scala2kmp(partialSig), new fr.acinq.bitcoin.crypto.musig2.IndividualNonce(remoteNonce.data.toArray), scala2kmp(remotePubKey))
    println(s"Manual Verification Result (Remote Sig): $result")
    
    val sessionNoTweak = fr.acinq.bitcoin.crypto.musig2.Session.create(aggregateNonce, scala2kmp(sighash), keyAggCache)
    val resultNoTweak = sessionNoTweak.verify(scala2kmp(partialSig), new fr.acinq.bitcoin.crypto.musig2.IndividualNonce(remoteNonce.data.toArray), scala2kmp(remotePubKey))
    println(s"Manual Verification Result (No Tweak): $resultNoTweak")
  }
}
