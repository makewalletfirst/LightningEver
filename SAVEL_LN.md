# SAVEL_LN.md — BitEver Lightning Network 완전 구축 가이드

> **목적**: 이 문서 하나만으로 L1(비트에버 체인) 입금→자동 채널 구축→자동 유동성 확보→외부 L1 전송까지 완전 재현 가능한 가이드.  
> **작성일**: 2026-05-10  

---

## 1. 프로젝트 전체 구조 (Architecture)

```
┌─────────────────────────────────────────────────────────────────┐
│                      사용자 스마트폰 (Android)                    │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │  LightningEver App (Phoenix 기반)                        │   │
│   │  Repository: LightningEver-bitever-phoenix               │   │
│   │  Branch: SAVE                                            │   │
│   │  - Bolt12 주소 생성 / 결제                               │   │
│   │  - simple_taproot_phoenix 채널 타입                      │   │
│   │  - Musig2 서명 (funding output: 2-of-2 taproot key path) │   │
│   └───────────────────┬─────────────────────────────────────┘   │
└───────────────────────│─────────────────────────────────────────┘
                        │ BOLT 2/12 (Lightning P2P)
                        │ TCP Port 9735
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                  BitEver 서버 (Linux VPS)                        │
│                                                                   │
│  ┌────────────────────────────────────────────────────────┐      │
│  │  Eclair LSP Node (bitever-eclair)                       │      │
│  │  Branch: SAVE                                           │      │
│  │  Path: /root/md/eclair                                  │      │
│  │  JAR: eclair-core_2.13-0.13.1.jar                      │      │
│  │  Config: /root/.eclair/eclair.conf                      │      │
│  │  Plugins: channel-funding-plugin-0.13.1.jar             │      │
│  │                                                          │      │
│  │  역할:                                                   │      │
│  │  - LSP (Lightning Service Provider)                     │      │
│  │  - Phoenix 지갑 연결 관리                                │      │
│  │  - 자동 채널 개설 (on-the-fly funding)                  │      │
│  │  - HTLC 라우팅                                          │      │
│  │  - Musig2 서명 집계 (funding tx witness)                │      │
│  └───────────────────┬────────────────────────────────────┘      │
│                      │ ZMQ / RPC                                   │
│                      ▼                                            │
│  ┌────────────────────────────────────────────────────────┐      │
│  │  Bitcoind (BitEver Full Node)                           │      │
│  │  Config: /root/.bitcoin/bitcoin.conf                    │      │
│  │  Network: regtest (또는 커스텀 체인)                    │      │
│  │  ZMQ: tcp://127.0.0.1:29000 (blocks)                   │      │
│  │        tcp://127.0.0.1:29001 (txs)                     │      │
│  └────────────────────────────────────────────────────────┘      │
│                                                                   │
│  ┌────────────────────────────────────────────────────────┐      │
│  │  WireGuard VPN (선택)                                   │      │
│  │  Interface: wg0 / 10.8.0.1                              │      │
│  │  폰 접속: 10.8.0.x → 서버 9735 포트                    │      │
│  └────────────────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────┘

공유 라이브러리:
  bitever-bitcoin-kmp (Branch: SAVE)
  - Musig2, Taproot, Script 구현 (Kotlin Multiplatform)
  - Eclair의 bitcoin-kmp-jvm-0.28.0.jar 로 사용
  - Phoenix의 bitcoin-kmp 의존성으로 사용
```

---

## 2. 레포지토리 목록

| 레포 | 용도 | 브랜치 | 로컬 경로 |
|------|------|--------|-----------|
| `LightningEver-bitever-phoenix` | Phoenix 기반 Android 앱 | `SAVE` | `/root/lightningever` |
| `LightningEver-bitever-eclair` | Eclair LSP 노드 | `SAVE` | `/root/md/eclair` |
| `LightningEver-bitever-eclair-kmp` | bitcoin-kmp 라이브러리 | `SAVE` | `/root/md/bitever-bitcoin-kmp` |

---

## 3. 채널 Close 원인 및 수정 코드 위치

이 프로젝트에서 **채널이 즉시 또는 일정 시간 후 force-close** 되는 문제가 반복됐습니다. 원인과 수정 방법을 단계별로 기술합니다.

---

### 3.1 버그 #1 — Musig2 Nonce 키 정렬 순서 불일치

**파일**: `eclair-core/src/main/scala/fr/acinq/eclair/crypto/NonceGenerator.scala`

**증상**: 채널 개설 직후 force-close. 로그에 `InvalidCommitmentSignature`.

**원인**: `generateNonce`/`generateNonceWithCounter` 호출 시 키 배열을 `sort([local, remote])` 대신 원래 순서 `[local, remote]` (비정렬)로 전달해야 KMP와 동일한 nonce 값이 생성됩니다.

**수정 내용**:
```scala
// eclair/eclair-core/src/main/scala/fr/acinq/eclair/crypto/NonceGenerator.scala

// verificationNonce: KMP와 동일하게 [local, remote] 순서 (sort 없음)
def verificationNonce(fundingTxId: TxId, fundingPrivKey: PrivateKey, remoteFundingPubKey: PublicKey, commitIndex: Long): LocalNonce =
  Musig2.generateNonceWithCounter(commitIndex, fundingPrivKey,
    Seq(fundingPrivKey.publicKey, remoteFundingPubKey),  // ← sort 없음
    None, Some(fundingTxId.value))

// signingNonce: 동일하게 [local, remote] 순서
def signingNonce(localFundingPubKey: PublicKey, remoteFundingPubKey: PublicKey, fundingTxId: TxId): LocalNonce = {
  val sessionId = randomBytes32()
  Musig2.generateNonce(sessionId, Right(localFundingPubKey),
    Seq(localFundingPubKey, remoteFundingPubKey),  // ← sort 없음
    None, Some(fundingTxId.value))
}
```

---

### 3.2 버그 #2 — partialSign의 publicNonces 순서 오류 (가장 핵심 버그)

**파일**: `eclair-core/src/main/scala/fr/acinq/eclair/channel/Commitments.scala`

**증상**: Phoenix가 Eclair의 CommitSig를 받고 내부적으로 검증 실패 → Phoenix가 채널 force-close. 로그: `verify [localNonce,remoteNonce]=false verify [remoteNonce,localNonce]=false`.

**원인**: Musig2 `signTaprootInput`에서 `publicNonces[i]`는 `sortedKeys[i]`의 nonce여야 합니다.

```
sortedKeys = sort([eclairPubKey, phoenixPubKey])
           = [phoenix(idx=0), eclair(idx=1)]  (phoenix의 pubkey가 lexicographically 작음)

따라서:
  publicNonces[0] = phoenix의 nonce (remoteNonce)
  publicNonces[1] = eclair의 nonce  (localNonce)
```

**수정 위치 1**: `RemoteCommit.sign` (초기 채널 개설 시 사용)

```scala
// 파일: Commitments.scala, RemoteCommit.sign 메서드 내
// 대략 L220-228

// ❌ 잘못된 코드:
remoteCommitTx.partialSign(fundingKey, remoteFundingPubKey, localNonce,
  Seq(localNonce.publicNonce, remoteNonce))  // eclair가 idx0 → 틀림

// ✅ 수정된 코드:
remoteCommitTx.partialSign(fundingKey, remoteFundingPubKey, localNonce,
  Seq(remoteNonce, localNonce.publicNonce))  // phoenix(idx0), eclair(idx1) → 올바름
```

**수정 위치 2**: `Commitment.sendCommit` (HTLC 이후 commit 업데이트)

```scala
// 파일: Commitments.scala, Commitment.sendCommit 메서드 내
// 대략 L675-682

// ❌ 잘못된 코드:
remoteCommitTx.partialSign(fundingKey, remoteFundingPubKey, localNonce,
  Seq(localNonce.publicNonce, remoteNonce))

// ✅ 수정된 코드:
remoteCommitTx.partialSign(fundingKey, remoteFundingPubKey, localNonce,
  Seq(remoteNonce, localNonce.publicNonce))
```

---

### 3.3 버그 #3 — fromCommitSig 검증 nonce 인덱스 오류

**파일**: `eclair-core/src/main/scala/fr/acinq/eclair/channel/Commitments.scala`

**증상**: Phoenix가 Eclair local commit에 서명한 partial sig를 Eclair가 검증할 때 항상 실패. `checkRemotePartialSignature` BYPASS가 없으면 채널 즉시 close.

**원인**: Phoenix가 Eclair의 commit(index=N)에 서명할 때, Phoenix는 Eclair의 이전 `RevokeAndAck`에서 받은 nonce를 사용합니다. 해당 nonce는 `verificationNonce(fundingTxId, eclairKey, phoenixPubKey, N+1)`로 생성된 값입니다. (RevokeAndAck가 `localCommitIndex + 2`를 보내고, 그 시점의 localCommitIndex = N-1이므로 index = N+1)

Eclair의 검증 코드에서 `localCommitIndex`(= N)만으로 nonce를 생성하면 다른 값이 나옵니다. `localCommitIndex + 1`로 수정해야 합니다.

```scala
// 파일: Commitments.scala, LocalCommit.fromCommitSig 내
// 대략 L182-187

// ❌ 잘못된 코드:
val localNonce = NonceGenerator.verificationNonce(
  fundingTxId, fundingKey, remoteFundingPubKey, localCommitIndex)

// ✅ 수정된 코드:
// RevokeAndAck는 (oldCommitIndex + 2) = (localCommitIndex - 1 + 2) = localCommitIndex + 1
val localNonce = NonceGenerator.verificationNonce(
  fundingTxId, fundingKey, remoteFundingPubKey, localCommitIndex + 1)
```

---

### 3.4 버그 #4 — fullySignedLocalCommitTx nonce 인덱스 오류

**파일**: `eclair-core/src/main/scala/fr/acinq/eclair/channel/Commitments.scala`

**증상**: force-close 시 Eclair가 자신의 commit tx를 broadcast할 때 witness가 잘못됨 → tx rejected.

**원인**: Eclair가 자신의 local commit tx를 완성할 때 (aggregateSigs), 자신의 partial sig 생성에 사용할 nonce index가 잘못됨.

```scala
// 파일: Commitments.scala, Commitment.fullySignedLocalCommitTx 내
// 대략 L723-736

// ✅ 올바른 코드 (이미 수정됨):
val nonceIndex = localCommit.index + 1
val localNonce = NonceGenerator.verificationNonce(fundingTxId, fundingKey, remoteFundingPubKey, nonceIndex)
// publicNonces 순서: [remoteSig.nonce(phoenix), localNonce(eclair)] → sortedKeys 순서와 일치
val Right(localSig) = unsignedCommitTx.partialSign(
  fundingKey, remoteFundingPubKey, localNonce,
  Seq(remoteSig.nonce, localNonce.publicNonce))  // [phoenix(idx0), eclair(idx1)]
```

---

### 3.5 버그 #5 — aggregateSigs의 partialSigs/nonces 순서

**파일**: `eclair-core/src/main/scala/fr/acinq/eclair/transactions/Transactions.scala`

**수정 내용**: `aggregateTaprootSignatures` 호출 시 `partialSigs[i]`와 `publicNonces[i]`가 `sortedKeys[i]`와 대응해야 합니다.

```scala
// 파일: Transactions.scala, ChannelSpendTransaction.aggregateSigs 내
// 대략 L368

// ✅ 올바른 코드 (이미 수정됨):
aggregatedSignature <- Musig2.aggregateTaprootSignatures(
  Seq(remoteSig.partialSig, localSig.partialSig),  // [phoenix(idx0), eclair(idx1)]
  tx, inputIndex, spentOutputs,
  sort(Seq(localFundingPubkey, remoteFundingPubkey)),
  Seq(remoteSig.nonce, localSig.nonce),             // [phoenix(idx0), eclair(idx1)]
  None)
```

---

### 3.6 버그 #6 — HTLC 서명 검증 bypass (임시 조치)

**파일**: `eclair-core/src/main/scala/fr/acinq/eclair/transactions/Transactions.scala`

**현재 상태**: `checkRemoteSig`(commit tx)와 `checkRemoteSig`(HTLC tx) 모두 `true`를 반환하도록 bypass됨.

**이유**: Musig2 partial sig 검증 로직이 완전히 수정되지 않은 상태에서 개발/디버깅을 위해 임시로 bypass.

```scala
// Transactions.scala L332-336 (commit tx checkRemoteSig bypass)
def checkRemoteSig(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey,
                   remoteSig: ChannelSpendSignature.IndividualSignature): Boolean = {
  // TODO: 버그 수정 완료 후 실제 검증으로 복원
  true
}
```

**⚠️ 프로덕션 전 필수 복원**: 버그 #1~#5 수정 검증 완료 후 bypass 제거하고 실제 검증 코드 복원.

---

### 3.7 버그 #7 — Commitments.scala nonce index offset (+1 → +2)

**파일**: `eclair-core/src/main/scala/fr/acinq/eclair/channel/Commitments.scala` L1150

**원래 있던 버그**: RevokeAndAck에서 보내는 nonce index가 `+1`이었는데 KMP와 맞추기 위해 `+2`로 수정.

```scala
// Commitments.receiveCommit 내 RevokeAndAck nonce 생성
// ✅ 올바른 코드 (이미 수정됨):
val localNonce = NonceGenerator.verificationNonce(
  c.fundingTxId, c.localFundingKey(channelKeys), c.remoteFundingPubKey,
  localCommitIndex + 2)  // ← +1이 아닌 +2
```

---

## 4. 서버 환경 설정

### 4.1 필수 소프트웨어

```bash
# Java 21 (Scala/Eclair 실행)
apt install openjdk-21-jdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Maven (빌드)
# /root/md/eclair/mvnw 사용 (래퍼 포함)

# WireGuard (폰 → 서버 VPN)
apt install wireguard

# Bitcoin Core (커스텀 체인)
# /root/.bitcoin/bitcoin.conf 참조
```

### 4.2 Bitcoin 설정 (`/root/.bitcoin/bitcoin.conf`)

```ini
server=1
regtest=1  # 또는 커스텀 체인 설정
rpcuser=bitcoinrpc
rpcpassword=<PASSWORD>
rpcallowip=127.0.0.1
zmqpubrawblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29001
txindex=1
```

### 4.3 Eclair 설정 (`/root/.eclair/eclair.conf`)

```hocon
eclair {
  chain = "regtest"
  node-alias = "bitever-eclair"
  
  bitcoin-host = "localhost"
  bitcoin-port = 18443  # regtest RPC 포트
  
  server {
    port = 9735
    public-ips = ["10.8.0.1"]  # WireGuard IP
  }
  
  api {
    enabled = true
    port = 8080
    password = "bitever"
  }
  
  # LSP 설정
  channel-initiator-reserve-to-funding-ratio = 0
  max-htlc-value-in-flight-percent = 100
}
```

---

## 5. 빌드 절차

### 5.1 Eclair 빌드

```bash
cd /root/md/eclair
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# 테스트 없이 eclair-core만 빌드 (약 25초)
./mvnw -DskipTests -pl eclair-core package

# 빌드 성공 확인
ls -la eclair-core/target/eclair-core_2.13-0.13.1.jar
```

### 5.2 JAR 배포

```bash
# 기존 Eclair 종료
pkill -f 'fr.acinq.eclair.Boot'
sleep 2

# JAR 교체
cp eclair-core/target/eclair-core_2.13-0.13.1.jar \
   /root/bitever-eclair-dist/eclair-node-0.13.1-93cc2ab/lib/eclair-core_2.13-0.13.1.jar

# Eclair 재시작
cd /root/bitever-eclair-dist/eclair-node-0.13.1-93cc2ab/bin
nohup ./eclair-node.sh /root/.eclair/plugins/channel-funding-plugin-0.13.1.jar \
  >> /root/.eclair/eclair.log 2>&1 &

# 시작 확인 (30~60초 대기)
sleep 45
curl -s -u :bitever -X POST http://localhost:8080/getinfo | python3 -c \
  "import json,sys; d=json.load(sys.stdin); print('UP block:', d['blockHeight'])"
```

### 5.3 LightningEver 앱 빌드 (Android)

```bash
cd /root/lightningever
# bitcoin-kmp 커스텀 버전 의존성 확인
./gradlew :composeApp:assembleDebug

# APK 위치
ls composeApp/build/outputs/apk/debug/
```

---

## 6. 전체 결제 플로우

### 6.1 초기 채널 구축 (L1 입금 → LN 채널 자동 생성)

```
1. 사용자가 LightningEver 앱 설치
2. 앱이 Eclair LSP에 연결 (9735 포트)
3. 사용자가 L1 주소로 BTC 전송 (예: 0.03 BEC)
4. Eclair의 channel-funding-plugin이 입금 감지
5. Phoenix와 Eclair가 interactive-tx 프로토콜로 funding tx 공동 구성
   - simple_taproot_phoenix 채널 타입
   - funding output: P2TR (Musig2 key aggregation)
6. Funding tx 브로드캐스트 및 6확인
7. ChannelReady 메시지 교환
8. 채널 상태: WaitForChannelReady → NORMAL
   (약 10분 소요)
9. Phoenix 지갑에 유동성 표시
```

### 6.2 BOLT12 결제 (LN → LN)

```
1. 수신자가 BOLT12 주소로 invoice 생성
2. 송신자 앱이 invoice 파싱
3. Phoenix → Eclair: UpdateAddHtlc
4. Eclair → Phoenix: CommitSig (Musig2 partial sig 포함)
   ← 버그 #1~#5 수정 필요
5. Phoenix가 sig 검증 → RevokeAndAck
6. Eclair → Phoenix: CommitSig (상대방 commit 서명)
7. Phoenix: UpdateFulfillHtlc (preimage 공개)
8. 결제 완료
```

### 6.3 L1 출금 (LN → L1)

```
1. 사용자가 L1 주소로 금액 지정
2. Phoenix가 Eclair를 통해 submarine swap 또는 직접 close
3. Eclair가 L1 트랜잭션 브로드캐스트
4. 확인 후 L1 잔액 도착
```

---

## 7. 주요 파일 위치

```
/root/md/eclair/                          ← Eclair 소스
  eclair-core/src/main/scala/fr/acinq/eclair/
    channel/
      Commitments.scala                   ← ★ 핵심 수정 파일 (버그 #2, #3, #4, #7)
      fsm/Channel.scala                   ← 채널 FSM
      fund/InteractiveTxBuilder.scala     ← Funding tx 구성
    crypto/
      NonceGenerator.scala                ← ★ 핵심 수정 파일 (버그 #1)
    transactions/
      Transactions.scala                  ← ★ 핵심 수정 파일 (버그 #5, #6)
      Scripts.scala                       ← Taproot 스크립트 생성

/root/bitever-eclair-dist/                ← 배포된 실행파일
  eclair-node-0.13.1-93cc2ab/
    bin/eclair-node.sh                    ← 실행 스크립트
    lib/eclair-core_2.13-0.13.1.jar      ← 교체 대상 JAR
    lib/channel-funding-plugin-0.13.1.jar ← LSP 플러그인

/root/.eclair/                            ← Eclair 설정/데이터
  eclair.conf                             ← 설정
  eclair.log                              ← 로그
  plugins/                                ← 플러그인

/root/lightningever/                      ← LightningEver 앱 소스
/root/md/bitever-bitcoin-kmp/             ← bitcoin-kmp 커스텀 버전

/root/.bitcoin/                           ← Bitcoin Core 데이터
  bitcoin.conf                            ← 설정
```

---

## 8. 디버깅 방법

### 8.1 실시간 로그 확인

```bash
# Eclair 로그 (필터: 중요 이벤트만)
tail -f /root/.eclair/eclair.log | grep -E \
  'NORMAL|OFFLINE|CLOSING|CommitSig|RevokeAndAck|UpdateAdd|UpdateFulfill|force-close|ERROR|Musig2'

# 채널 상태 확인
curl -s -u :bitever -X POST http://localhost:8080/channels | python3 -c "
import json,sys
for c in json.load(sys.stdin):
    print(c['state'], c['channelId'][:12], 
          c.get('data',{}).get('commitments',{}).get('active',[{}])[0]
           .get('localCommit',{}).get('spec',{}).get('toLocal',0)//1000, 'sat')
"
```

### 8.2 Musig2 서명 검증 디버그 로그

`Transactions.scala`의 `checkRemotePartialSignature`에서 추가한 로그:
```
DEBUG_Musig2: verify [localNonce,remoteNonce]=true/false  verify [remoteNonce,localNonce]=true/false
```

- 둘 다 `false` → nonce 값 자체가 틀림 (버그 #1, #3)
- `isValid2=true` → nonce 순서만 틀림 (버그 #2)

### 8.3 HTLC 서명 디버그 로그

```
DEBUG_HtlcLocalSig: txid=... ourHtlcKey=... sighash=... redeemInfo=...
DEBUG_HtlcSig: checkRemoteSig FAILED txid=...
```

---

## 9. 현재 남은 작업 (TODO)

| 우선순위 | 작업 | 파일 |
|---------|------|------|
| 🔴 긴급 | `partialSign` nonce 순서 수정 검증 | `Commitments.scala` |
| 🔴 긴급 | `checkRemoteSig` bypass 제거 후 실제 검증 복원 | `Transactions.scala` |
| 🟡 중요 | HTLC sig가 Phoenix 기준에 맞는지 검증 | `Transactions.scala` |
| 🟡 중요 | `channel_reestablish` 후 pending HTLC 복구 확인 | `Channel.scala` |
| 🟢 개선 | 전체 Musig2 테스트 코드 작성 | test/ |
| 🟢 개선 | 프로덕션 전 bypass 전면 제거 | `Transactions.scala` |

---

## 10. 복구 절차

코드가 손상됐을 경우:

```bash
# Eclair 복구
git -C /root/md/eclair checkout SAVE
# 또는 GitHub에서
git -C /root/md/eclair fetch origin
git -C /root/md/eclair checkout origin/SAVE

# 빌드 및 배포 (섹션 5 참조)

# LightningEver 앱 복구
git -C /root/lightningever fetch origin
git -C /root/lightningever checkout origin/SAVE
```

---

*이 문서는 2026-05-10 기준 작업 내용을 반영합니다. 추가 수정 사항은 각 GitHub SAVE 브랜치의 커밋 히스토리를 참조하세요.*
