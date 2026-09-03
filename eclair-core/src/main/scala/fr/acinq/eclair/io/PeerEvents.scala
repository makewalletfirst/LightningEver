/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.io

import akka.actor.ActorRef
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol.{NodeAddress, UnknownMessage}

import scala.concurrent.duration._

sealed trait PeerEvent

case class PeerCreated(peer: ActorRef, nodeId: PublicKey) extends PeerEvent

case class ConnectionInfo(address: NodeAddress, peerConnection: ActorRef, localInit: protocol.Init, remoteInit: protocol.Init)

case class PeerConnected(peer: ActorRef, nodeId: PublicKey, connectionInfo: ConnectionInfo) extends PeerEvent

case class PeerDisconnected(peer: ActorRef, nodeId: PublicKey) extends PeerEvent

case class LastChannelClosed(peer: ActorRef, nodeId: PublicKey) extends PeerEvent

case class PongReceived(nodeId: PublicKey, latency: FiniteDuration) extends PeerEvent

case class UnknownMessageReceived(peer: ActorRef, nodeId: PublicKey, message: UnknownMessage, connectionInfo: ConnectionInfo) extends PeerEvent

/** Phoenix FCM token (lightning message tag 35017). Published on the EventStream so plugins
 *  (e.g. fcm-push-plugin) can keep a peer_nodeId → token map and send push notifications. */
case class FcmTokenRegistered(nodeId: PublicKey, token: String, platform: String) extends PeerEvent

/** Phoenix UnsetFCMToken (lightning message tag 35019). The peer wants to stop receiving pushes. */
case class FcmTokenUnregistered(nodeId: PublicKey) extends PeerEvent

/**
 * [LightningEver] Wallet pre-registered its swap-in addresses (lightning message tag 35021).
 * Used by fcm-push-plugin to monitor L1 for deposits while the wallet is offline and wake it up
 * with a push when a deposit lands.
 */
case class SwapInAddressesRegistered(nodeId: PublicKey, addresses: List[String]) extends PeerEvent

/**
 * Emitted by [[PeerReadyNotifier]] when it is asked to wait for a peer that is not currently connected.
 * Push-notification plugins (e.g. fcm-push-plugin) subscribe to this and send a wake-up notification
 * so that mobile clients reconnect within the wake-up timeout window.
 *
 * @param reason short label describing the origin of the wake-up request (e.g. "OnionMessage",
 *               "TrampolinePayment") — used as the FCM `reason` field so the wallet can act on it.
 */
case class WakeUpPeerRequested(nodeId: PublicKey, reason: String) extends PeerEvent
