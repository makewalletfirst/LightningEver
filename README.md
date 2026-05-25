# LightningEver Eclair LSP (BitEver Fork)

Welcome to the **LightningEver Eclair LSP** repository. This is a custom fork and patched build of **ACINQ Eclair v0.13.1** tailored for the **BitEver (BEC)** L1 blockchain network. It serves as the single Lightning Service Provider (LSP) for mobile wallets in the LightningEver ecosystem (built on Phoenix Android and `lightning-kmp`).

---

## 1. System Architecture Overview

LightningEver is a custom Lightning Network stack operating on top of the **BitEver (BEC)** Layer 1 blockchain—a custom Bitcoin fork featuring Taproot, MuSig2, P2TR, and a custom chain hash.

Below is the high-level architecture of the ecosystem:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              BitEver (BEC) L1                               │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • Custom Bitcoin fork (Taproot, MuSig2, P2TR, unique chainHash)             │
│  • Independent mining & blockchain network                                  │
│  • bitcoind RPC: 10.8.0.6:8334 | ZMQ: 28332/28333                           │
│  • Electrs Indexer: electrs.ever-chain.xyz:50001                            │
│  • chainHash: 6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000... │
└─────────────────────────────────────────────────────────────────────────────┘
          ▲                              ▲                          ▲
          │ RPC + ZMQ                    │ Electrum RPC             │ Electrum
          │                              │                          │
┌─────────┴───────────────┐  ┌───────────┴────────────┐  ┌──────────┴─────────┐
│   Eclair LSP            │  │  Phoenix Android       │  │  Phoenix Android   │
│   (BitEver Fork)        │  │  = LightningEver App   │  │  = LightningEver   │
│   ─────────────────     │◀─┤  (Phone A)             │  │  (Phone B)         │
│   • Node ID:            │  │  ─────────────────     │  │  ─────────────────│
│     0311fb42898e...     │  │  • Uses lightning-kmp  │  │  • lightning-kmp   │
│   • IP: 152.67.210.39   │  │  • LSP peer: 0311fb... │  │  • LSP peer: 03... │
│   • Port: 9735 (P2P)    │  │                        │  │                    │
│   • Port: 8080 (REST)   │  │                        │  │                    │
│   • + Channel Funding   │  │                        │  │                    │
│     plugin              │  │                        │  │                    │
│   • SQLite Database     │  │                        │  │                    │
└─────────────────────────┘  └────────────────────────┘  └────────────────────┘
          │                              ▲                          ▲
          │       Lightning P2P          │                          │
          ├──────────────────────────────┘                          │
          │       Lightning P2P                                     │
          └─────────────────────────────────────────────────────────┘
```

### Core Architecture Facts
* **Single LSP Structure:** All mobile wallet traffic is routed through the Eclair LSP (`0311fb42...`). Direct peer-to-peer channels between mobile phones are not supported.
* **L1 Interface Routing:** 
  * **Eclair LSP** connects to the BEC L1 nodes via both **bitcoind JSON-RPC** (port `8334`) and **ZMQ** (ports `28332/28333`), and tracks mempool details using **Electrs** (port `50001`).
  * **Phoenix Android** (mobile clients) communicates solely with **Electrs** for L1 address tracking, swap-in scans, and fee estimations. It never accesses the bitcoind RPC directly.

---

## 2. Core Technology Stack

* **Language & Runtime:** Scala, JVM (Java OpenJDK 21), and SBT/Maven build tools.
* **Concurrency Model:** Actor-based concurrency using Akka/Pekko (Switchboard, Peer, Channel, and Router daemons).
* **Database:** SQLite (embedded) for channel states, payment histories, liquidity records, and preimages.
* **Lightning Protocols & Standards:**
  * **BOLT 8 (Noise XK Handshake):** Cryptographically secures P2P transport.
  * **BOLT 12 (Offers & Blinded Paths):** Enables dynamic, invoice-less payments and privacy-preserving onion relays.
  * **Dual Funding & Splice (BOLT 2):** Supports interactive collaborative tx building for channel creation and dynamic splicing.
  * **Taproot & MuSig2 (`simple_taproot_phoenix` type):** Uses advanced Schnorr/MuSig2 signatures for funding and commitments.
* **Push Services:** Integrated with **Google Firebase Cloud Messaging (FCM)** via event streams to wake up backgrounded or offline mobile wallets.

---

## 3. Verified Functional Milestones

The LSP successfully implements and verifies 7 critical transaction flows:

1. **L1 Swap-in:** Mobile user receives L1 BEC -> LSP automatically opens a dual-funded Taproot channel, contributing a **50M sat auto-funding** balance.
2. **Dual-Funded Bolt12 payments:** Successful payments when channels are pre-established on both the sender and receiver sides.
3. **On-the-Fly (OTF) Channel Creation:** If Phone A sends a Bolt12 payment to Phone B (who does not yet have a channel), the LSP intercepts the HTLC, holds it, triggers Phone B to connect, and creates a channel on-the-fly using `from_future_htlc`.
4. **Splice-Out:** Seamlessly sending transactions from the Lightning channel directly to an external L1 BEC address.
5. **Specified Address Mutual Close:** Gracesfully closes the channel, distributing final balances using MuSig2 signatures.
6. **Force Close:** Recovers channel balances using unilateral commitment transactions. Features a shortened **144-block (~24 hours)** CSV timelock (down from 720 blocks).
7. **Request Liquidity (Splice-In):** Dynamically increases channel capacity, bypassing normal channel reserve validations.

---

## 4. Key Modifications & Patches (Up to 260523)

To ensure full compatibility with the custom BitEver chain, and to optimize the offline/OTF mobile user experience, Eclair includes the following core custom patches:

### A. MuSig2 Nonce Sorting (Fixes Splice & Commits)
* **Locations:** `InteractiveTxBuilder.scala` (lines ~116, ~1000), `ChannelOpenSingleFunded.scala` (lines ~230, ~306), and `Helpers.scala` (lines ~830, ~886, ~966).
* **Fix:** Upstream code did not sort keys when generating MuSig2 nonces. This patch sorts keys to guarantee matching nonce arrays `[remote, local]` between Phoenix and Eclair, preventing signature mismatch failures during dual-funding, commitments, and mutual closes.

### B. BitEver L1 Compatibility Patches
* **`Setup.scala` Block Sync Bypass:** Bypasses checks for `initialBlockDownload`, `verificationProgress > 0.999`, and `headers == blocks`. Because BEC is a custom developer chain with lower cumulative work, these checks would otherwise block Eclair from starting.
* **Fee Fallback Mechanism:** Maps unknown chain hashes (non-regtest/non-signet) to a combined `BitcoinCoreFeeProvider + ConstantFeeProvider` to prevent startup errors when `estimatefee` RPC calls return empty.
* **`Peer.kt` Fee Fallback:** Mobile KMP falls back to `MinimumFeeratePerKw` if Electrs fee estimation fails due to low block height.

### C. Developer Security Bypasses (DEV-BYPASS)
* **`Transactions.scala` Bypasses:** 
  * `checkRemoteSig` set to **always return true**.
  * `checkRemotePartialSignature` (MuSig2) set to **always return true**.
  * `checkRemoteHtlcSig` set to **always return true**.
* **`Helpers.scala` Validation Bypass:** Bypasses `validateParamsDualFundedNonInitiator` to allow `fundingAmount = 0` if `usesOnTheFlyFunding` is true, enabling channel-less nodes to trigger OTF funding.
* **`InteractiveTxBuilder.scala` Reserve Bypass:** Disables channel reserve constraints during splicing/liquidity requests. This lets mobile clients splice even if their local balances fall below the standard 1% reserve threshold.
* **`Helpers.scala` Commit Nonces Bypass:** Bypasses `MissingCommitNonce` checks during reconnection of Taproot channels to prevent accidental force-closes.

### D. Offline Push Messaging (BOLT 12 & Swap-In)
* **`Peer.scala` FCM Token Handling:** Parses custom wire tags **`35017`** (`FCMToken`) and **`35019`** (`UnsetFCMToken`), publishing events to wake up offline mobile nodes.
* **`PeerReadyNotifier.scala` Trigger:** Publishes `WakeUpPeerRequested` when an offline peer is targeted by an incoming payment. A separate plugin (`fcm-push-plugin`) intercepts this event and fires a push notification to wake the phone up.
* **`Peer.scala` Offline Swap-In Address:** Custom tag **`35021`** (`SwapInAddressRegister`) allows mobile wallets to register their swap-in addresses with the LSP while they are offline.

### E. Graceful Fail on Fee Insufficiency (`Commitments.scala`)
* **Location:** `Commitments.scala` (line ~1162)
* **Fix:** When Phone A attempts an OTF payment to Phone B but the payment amount is lower than the LSP channel creation fee:
  * Instead of throwing a `CommitSigCountMismatch` which causes an abrupt **force-close** of Phone A's channel, the mismatch is bypassed.
  * The payment gracefully fails and falls back to a clean HTLC rejection flow. Phone A receives a "Payment Failed" warning in the UI, and the channel remains in `NORMAL` status.

---

## 5. ⚠️ Crucial Safety Warnings (Precautions)

> [!WARNING]
> **DEVELOPMENT & TEST USE ONLY!**
> The `Transactions.scala` bypasses (`checkRemoteSig`, `checkRemotePartialSignature`, and `checkRemoteHtlcSig` set to `true`) completely skip cryptographic verification of remote signatures on the LSP side.
> 
> * **DO NOT** deploy this codebase on Bitcoin mainnet or any live public network.
> * Doing so will result in immediate loss of funds, as anyone could construct invalid states and empty your channels.
> * For production deployment, all 8 `DEV-BYPASS` patches must be removed and replaced with robust signature validations.

---

## 6. Build Instructions

### Prerequisites
* OpenJDK 21 (configured in your shell environment).
* Maven Wrapper (`mvnw`).

### Building Eclair Core JAR
To build the eclair-core module and compile the modified Scala/Java source code while skipping testing phases, run:

```bash
# Set your JDK path
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Clean and package the core module
./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true
```

The resulting compiled library will be outputted to:
`eclair-core/target/eclair-core_2.13-0.13.1.jar`

---

## 7. Deployment & Operations

### A. Deploying the Updated JAR
1. Terminate any running Eclair LSP daemons:
   ```bash
   pkill -f 'fr.acinq.eclair.Boot'
   ```
2. Copy the newly built `eclair-core` JAR file into your active distribution `lib` directory, overwriting the old library:
   ```bash
   cp eclair-core/target/eclair-core_2.13-0.13.1.jar \
      /root/bitever-eclair-dist/eclair-node-0.13.1-93cc2ab/lib/eclair-core_2.13-0.13.1.jar
   ```

### B. Starting the Daemon
Navigate to your distribution binary directory and execute the boot script, providing the path to the required `channel-funding-plugin-0.13.1.jar` (and `fcm-push-plugin-0.13.1.jar` if offline wake-ups are enabled):

```bash
cd /root/bitever-eclair-dist/eclair-node-0.13.1-93cc2ab/bin

# Start in background, logging to your eclair directory
nohup ./eclair-node.sh /root/.eclair/plugins/channel-funding-plugin-0.13.1.jar \
  >> /root/.eclair/eclair.log 2>&1 &
```

> [!IMPORTANT]
> **Always launch with the channel-funding plugin argument.** If omitted, auto-channel generation and LSP funding overrides will fail.

### C. Activating On-the-Fly (OTF) Payments
Upon every startup of the daemon, you **MUST** send an administrative REST command to activate OTF payments (`from_future_htlc` feature mapping):

```bash
curl -s -u :bitever -X POST http://localhost:8080/enablefromfuturehtlc
# Expected response: {"enabled":true}
```

### D. Useful Operational Commands
Eclair is managed using standard JSON-RPC over REST (port `8080`). Below are standard diagnostic curl snippets:

```bash
# Check LSP status (node ID, block height, etc.)
curl -s -u :bitever -X POST http://localhost:8080/getinfo | jq .

# List all open channels with node ID and state
curl -s -u :bitever -X POST http://localhost:8080/channels | jq '.[] | {channelId, state, nodeId}'

# Query details of a specific channel
curl -s -u :bitever -X POST http://localhost:8080/channel -d "channelId=<CHANNEL_ID>" | jq .

# List connected active peers
curl -s -u :bitever -X POST http://localhost:8080/peers | jq .

# Live stream LSP node logs
tail -f /root/.eclair/eclair.log
```
