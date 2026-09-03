package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey, XonlyPublicKey}
import fr.acinq.bitcoin.scalacompat.Musig2.{IndividualNonce, LocalNonce, SecretNonce}
import fr.acinq.bitcoin.scalacompat._
import scodec.bits.ByteVector

case class SwapInProtocol(userPublicKey: PublicKey, serverPublicKey: PublicKey, userRefundKey: PublicKey, refundDelay: Int) {
  val sortedKeys: Seq[PublicKey] = Scripts.sort(Seq(userPublicKey, serverPublicKey))
  private val internalPublicKey: XonlyPublicKey = Musig2.aggregateKeys(sortedKeys)

  private val refundScript: Seq[ScriptElt] = Seq(
    OP_PUSHDATA(userRefundKey.xOnly),
    OP_CHECKSIGVERIFY,
    OP_PUSHDATA(Script.encodeNumber(refundDelay.toLong)),
    OP_CHECKSEQUENCEVERIFY
  )
  private val scriptLeaf = ScriptTree.Leaf(refundScript)
  private val scriptTree: ScriptTree = scriptLeaf

  val pubkeyScript: Seq[ScriptElt] = Script.pay2tr(internalPublicKey, Some(scriptTree))
  val serializedPubkeyScript: ByteVector = Script.write(pubkeyScript)

  def address(chainHash: ByteVector32): String = {
    val prefix = if (chainHash == Block.LivenetGenesisBlock.hash.value) "bc"
    else if (chainHash == Block.Testnet3GenesisBlock.hash.value || chainHash == Block.SignetGenesisBlock.hash.value) "tb"
    else "bcrt"
    fr.acinq.bitcoin.Bech32.encodeWitnessAddress(prefix, 1, Script.write(Script.pay2tr(internalPublicKey, None)).drop(2).toArray)
  }

  private val witnessLog = org.slf4j.LoggerFactory.getLogger("A1-WITNESS")

  def witness(fundingTx: Transaction, index: Int, parentTxOuts: Seq[TxOut], userNonce: IndividualNonce, serverNonce: IndividualNonce, userPartialSig: ByteVector32, serverPartialSig: ByteVector32): Either[Throwable, ScriptWitness] = {
    val publicKeys = Seq(userPublicKey, serverPublicKey)
    val publicNonces = Seq(userNonce, serverNonce)
    val sigs = Seq(userPartialSig, serverPartialSig)
    val result = Musig2.aggregateTaprootSignatures(sigs, fundingTx, index, parentTxOuts, publicKeys, publicNonces, Some(scriptTree))
    result match {
      case Left(err) =>
        witnessLog.error(s"[A1-WITNESS] aggregateTaprootSignatures FAILED: ${err.getMessage} | txid=${fundingTx.txid} idx=$index | userKey=${userPublicKey.toString.take(16)} serverKey=${serverPublicKey.toString.take(16)} | userSig=${userPartialSig.toHex.take(16)} serverSig=${serverPartialSig.toHex.take(16)}")
      case Right(_) =>
        witnessLog.info(s"[A1-WITNESS] aggregateTaprootSignatures SUCCESS txid=${fundingTx.txid} idx=$index")
    }
    result.map { aggregateSig =>
      Script.witnessKeyPathPay2tr(aggregateSig)
    }
  }

  def witnessRefund(userSig: ByteVector64): ScriptWitness =
    Script.witnessScriptPathPay2tr(internalPublicKey, scriptLeaf, ScriptWitness(Seq(userSig)), scriptTree)

  def signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOuts: Seq[TxOut], userPrivateKey: PrivateKey, privateNonce: SecretNonce, userNonce: IndividualNonce, serverNonce: IndividualNonce): Either[Throwable, ByteVector32] = {
    scala.util.Try {
      // KMP 방식과 동일: unsorted [user, server] 순서로 publicKeys와 publicNonces 전달
      // KMP: publicKeys=listOf(user,server), publicNonces=listOf(userNonce,serverNonce)
      val publicKeys = Seq(userPublicKey, serverPublicKey)
      val publicNonces = Seq(userNonce, serverNonce)
      Musig2.signTaprootInput(userPrivateKey, fundingTx, index, parentTxOuts, publicKeys, privateNonce, publicNonces, Some(scriptTree))
    }.toEither.flatMap(identity)
  }

  def signSwapInputRefund(fundingTx: Transaction, index: Int, parentTxOuts: Seq[TxOut], userPrivateKey: PrivateKey): ByteVector64 = {
    val leafHash = scriptLeaf.hash()
    Transaction.signInputTaprootScriptPath(userPrivateKey, fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, leafHash)
  }

  def signSwapInputServer(fundingTx: Transaction, index: Int, parentTxOuts: Seq[TxOut], serverPrivateKey: PrivateKey, privateNonce: SecretNonce, userNonce: IndividualNonce, serverNonce: IndividualNonce): Either[Throwable, ByteVector32] = {
    scala.util.Try {
      // KMP 방식과 동일: unsorted [user, server] 순서로 publicKeys와 publicNonces 전달
      // KMP: publicKeys=listOf(user,server), publicNonces=listOf(userNonce,serverNonce)
      val publicKeys = Seq(userPublicKey, serverPublicKey)
      val publicNonces = Seq(userNonce, serverNonce)
      Musig2.signTaprootInput(serverPrivateKey, fundingTx, index, parentTxOuts, publicKeys, privateNonce, publicNonces, Some(scriptTree))
    }.toEither.flatMap(identity)
  }

  def generateServerNonce(randomBytes: ByteVector32): LocalNonce = {
    Musig2.generateNonce(randomBytes, Right(serverPublicKey), sortedKeys, Some(scriptLeaf.hash()), None)
  }
}

object SwapInProtocol {
}
