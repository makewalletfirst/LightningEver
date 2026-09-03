package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{SigHash, SigVersion}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat._
import scodec.bits.ByteVector

/**
 * Legacy swap-in protocol — P2WSH 2-of-2 multisig with CSV refund branch.
 *
 * Miniscript policy: and(pk(<user_key>),or(99@pk(<server_key>),older(<delayed_refund>)))
 *
 * Mirrors KMP's `SwapInProtocolLegacy` so that LSP can sign on the server side
 * for legacy (bc1q…) swap-in addresses the wallet may still generate.
 *
 * @param userPublicKey   user pubkey (derived from user seed on the phone)
 * @param serverPublicKey server pubkey (derived from LSP swap-in xpub per remote nodeId)
 * @param refundDelay     CSV refund delay (typically 25920)
 */
case class SwapInProtocolLegacy(userPublicKey: PublicKey, serverPublicKey: PublicKey, refundDelay: Int) {

  /** Redeem script of the P2WSH 2-of-2 multisig (with CSV refund branch). */
  val redeemScript: Seq[ScriptElt] = Seq(
    OP_PUSHDATA(userPublicKey), OP_CHECKSIGVERIFY,
    OP_PUSHDATA(serverPublicKey), OP_CHECKSIG, OP_IFDUP,
    OP_NOTIF,
      OP_PUSHDATA(Script.encodeNumber(refundDelay.toLong)), OP_CHECKSEQUENCEVERIFY,
    OP_ENDIF
  )

  val pubkeyScript: Seq[ScriptElt] = Script.pay2wsh(redeemScript)
  val serializedPubkeyScript: ByteVector = Script.write(pubkeyScript)

  /** Witness with both user + server signatures (the normal swap-in path). */
  def witness(userSig: ByteVector64, serverSig: ByteVector64): ScriptWitness = {
    ScriptWitness(Seq(
      Scripts.der(serverSig, SigHash.SIGHASH_ALL),
      Scripts.der(userSig, SigHash.SIGHASH_ALL),
      Script.write(redeemScript)
    ))
  }

  /** Refund witness: user-only after CSV delay. */
  def witnessRefund(userSig: ByteVector64): ScriptWitness = {
    ScriptWitness(Seq(
      ByteVector.empty,
      Scripts.der(userSig, SigHash.SIGHASH_ALL),
      Script.write(redeemScript)
    ))
  }

  /** Sign a swap-in input with the user key (called on phone side, not used here on LSP but kept for symmetry). */
  def signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOut: TxOut, userPrivateKey: PrivateKey): ByteVector64 = {
    require(userPrivateKey.publicKey == userPublicKey, "user private key does not match expected user public key")
    val sigDER = Transaction.signInput(fundingTx, index, Script.write(redeemScript), SigHash.SIGHASH_ALL, parentTxOut.amount, SigVersion.SIGVERSION_WITNESS_V0, userPrivateKey)
    Crypto.der2compact(sigDER)
  }

  /** Sign a swap-in input with the server key (called on LSP side). */
  def signSwapInputServer(fundingTx: Transaction, index: Int, parentTxOut: TxOut, serverPrivateKey: PrivateKey): ByteVector64 = {
    require(serverPrivateKey.publicKey == serverPublicKey, "server private key does not match expected server public key")
    val sigDER = Transaction.signInput(fundingTx, index, Script.write(redeemScript), SigHash.SIGHASH_ALL, parentTxOut.amount, SigVersion.SIGVERSION_WITNESS_V0, serverPrivateKey)
    Crypto.der2compact(sigDER)
  }
}
