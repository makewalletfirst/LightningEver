# LightningEver-bitever-eclair (SAVE Branch)

BitEver Lightning Network의 LSP(Lightning Service Provider) 노드입니다.  
Phoenix 기반 LightningEver 앱과 `simple_taproot_phoenix` 채널 타입으로 연결됩니다.

## 핵심 수정 사항 (버그 수정 요약)

### 버그 #1 — NonceGenerator.scala: 키 순서 (비정렬)
`Musig2.generateNonce` 호출 시 `[local, remote]` 순서 유지 (sort 없음).

### 버그 #2 — Commitments.scala: partialSign nonce 순서 ★가장 중요★
`sortedKeys = sort([eclairKey, phoenixKey]) = [phoenix(idx0), eclair(idx1)]`이므로  
`publicNonces = [remoteNonce(phoenix), localNonce(eclair)]` 순서로 전달.

```scala
// RemoteCommit.sign 및 Commitment.sendCommit 내부
remoteCommitTx.partialSign(fundingKey, remoteFundingPubKey, localNonce,
  Seq(remoteNonce, localNonce.publicNonce))  // ✅ [phoenix(0), eclair(1)]
```

### 버그 #3 — Commitments.scala: fromCommitSig nonce 인덱스
`verificationNonce(..., localCommitIndex + 1)` 사용 (RevokeAndAck 전송 시와 동일).

### 버그 #4 — Commitments.scala: RevokeAndAck nonce 인덱스
`verificationNonce(..., localCommitIndex + 2)` 사용 (KMP와 동일).

### 버그 #5 — Transactions.scala: aggregateSigs 순서
`Seq(remoteSig.nonce, localSig.nonce)` = `[phoenix(0), eclair(1)]`.

## 빌드 방법

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -DskipTests -pl eclair-core package
```

## 배포 방법

```bash
pkill -f 'fr.acinq.eclair.Boot'; sleep 2
cp eclair-core/target/eclair-core_2.13-0.13.1.jar \
   /root/bitever-eclair-dist/eclair-node-0.13.1-93cc2ab/lib/eclair-core_2.13-0.13.1.jar
cd /root/bitever-eclair-dist/eclair-node-0.13.1-93cc2ab/bin
nohup ./eclair-node.sh /root/.eclair/plugins/channel-funding-plugin-0.13.1.jar \
  >> /root/.eclair/eclair.log 2>&1 &
```

## 주요 수정 파일

- `eclair-core/src/main/scala/fr/acinq/eclair/crypto/NonceGenerator.scala`
- `eclair-core/src/main/scala/fr/acinq/eclair/channel/Commitments.scala`
- `eclair-core/src/main/scala/fr/acinq/eclair/transactions/Transactions.scala`

## ⚠️ 프로덕션 전 필수 작업
`Transactions.scala`의 `checkRemoteSig` bypass 제거 후 실제 검증 코드 복원.

## 전체 구조
`SAVEL_LN.md` 참조 (레포 루트).

---

## 260521OFFBOLT12 추가 변경 (이번 브랜치)

**BOLT12 offer 의 오프라인 수신** 을 가능하게 하기 위한 LSP 측 변경. 폰 B 가 백그라운드/종료 상태이어도 폰 A 가 BOLT12 offer 주소로 송금하면 push 로 깨워서 자동 결제 도착.

### 변경 파일

| 파일 | 변경 |
|---|---|
| `io/Peer.scala` | tag 35017 (FCMToken) 의 silent-accept 제거 → 토큰 파싱 + EventStream publish. tag 35019 (UnsetFCMToken) 핸들러도 추가. |
| `io/PeerEvents.scala` | 새 이벤트 3종: `FcmTokenRegistered`, `FcmTokenUnregistered`, `WakeUpPeerRequested` |
| `io/PeerReadyNotifier.scala` | `waitForPeerConnected` 진입 시 `WakeUpPeerRequested` publish — fcm-push-plugin 이 이걸 받아 FCM push 발사 |
| `payment/relay/NodeRelay.scala` | dev-bypass(`walletNodeId_opt = Some(...)` 강제) 를 ACINQ 원본 feature-gated 로직으로 복원. 추가로 `attemptWakeUp` 호출 두 곳에 `&& false` 가드 — NodeRelay 측 wake-up 분기는 차단하고 MessageRelay 측만 켠다 (sender-side reserve violation 으로 인한 force-close 차단). |

### 동반 운영 설정

- `eclair.conf` 에 `eclair.peer-wake-up { enabled = true; timeout = 30 seconds }` 추가
- plugin 두 개 같이 로드: `channel-funding-plugin`, `fcm-push-plugin` (별도 레포)

### 검증된 시나리오

L1→swap-in(taproot/legacy), Request Liquidity, 폰A→폰B(online) BOLT11/BOLT12, 폰A→폰B(offline) BOLT12, mutual close, force close 모두 정상.

자세한 흐름 / 원리 / 사고 사례는 `260521OFFBOLT12.md` 참조 (LightningEver 메인 문서 폴더).

---

## 260522_OFFSWAPIN 추가 변경 (이 브랜치)

**오프라인 스왑인 입금 자동 감지** — 폰이 swap-in 주소를 발급한 후 앱을 켜두지 않아도 LSP 가 L1 입금을 감지하고 폰을 깨워 자동으로 채널을 생성하는 흐름. 폰 측 wire 메시지 (KMP 변경) + LSP 측 수신 핸들러를 추가.

### 변경 파일

| 파일 | 변경 |
|---|---|
| `io/Peer.scala` | 신규 사설 tag **35021** (`SwapInAddressRegister`) 핸들러. wire 페이로드 `[u16 count] [u16 len][len bytes ASCII]*` 파싱 후 `SwapInAddressesRegistered` EventStream publish |
| `io/PeerEvents.scala` | 새 이벤트 `SwapInAddressesRegistered(nodeId, addresses: List[String])` 추가 |

### 운영 메모

- LSP 의 fcm-push-plugin 측 EventStream 구독은 **일시 비활성** (BOLT12 offline 결제와 동시 발동 시 channel reserve violation 으로 force-close 재현됐기 때문). 폰이 35021 메시지를 보내도 LSP 가 receive 한 뒤 publish 까지만 하고 그 뒤 처리하지 않는다.
- 안전 가드 추가 + 검증 완료 후 fcm-push-plugin 의 subscribe 한 줄을 복원하면 자동 동작.
- 자세한 흐름 / 원인 / 운영 결정은 LightningEver 프로젝트의 `260522FCM.md` 참조.

---

## 260523 추가 변경 (이 브랜치)

**Commit sig count mismatch 시 force-close 차단** — 채널 없는 폰에 BOLT12 송금 시 송금액이 LSP 채널 개설 fee 보다 작은 경우 발생하던 race condition 으로 인한 sender 측 force-close 를 막는다.

### 시나리오

폰A → 채널 없는 폰B BOLT12 10K sat 송금 시도:
1. LSP 가 폰A 채널에 splice 형식으로 fee 추가 협상 시작 (on-the-fly funding)
2. 양측 splice tx 협상 완료 (TxComplete)
3. KMP 가 fee 부족 인지하고 `TxAbort("splice aborted")` 보냄 — 그러나 LSP 는 이미 협상 끝
4. LSP active funding 2개 vs 폰A 의 CommitSig 1개 → 본래 동작은 `CommitSigCountMismatch` → force-close
5. 사용자 입장에서는 단순 "수수료 부족" 결제 실패가 적절

### 변경 파일

- `eclair-core/src/main/scala/fr/acinq/eclair/channel/Commitments.scala` (line 1162 부근)
  - `case _: CommitSig if active.size > 1 => return Left(CommitSigCountMismatch(...))` 를 BYPASS 로 교체
  - CommitSig 1개를 latest active commitment 에 적용 (force-close 회피)
  - 결제는 자연스럽게 HTLC fail 흐름으로 떨어져 폰A UI 에 "결제 실패" 표시
  - 채널은 NORMAL 상태 그대로 유지

### 검증

폰 시연으로 확인:
- 10K sat 송금 (LSP fee 미달) → 깨끗한 fail, force-close 없음
- 100K sat 송금 (LSP fee 충분) → 정상 결제

이전 brnach `260522_OFFSWAPIN` 의 모든 변경 포함.
