# BitEver Eclair — 260517_FIN 브랜치 (FINAL)

LightningEver LSP의 **최종 완성판**. ACINQ Eclair v0.13.1을 BitEver L1 체인용으로 fork하고 11개 패치를 적용한 빌드. 7가지 사용자 시나리오 전부 지원.

## 7가지 검증된 마일스톤

1. ✅ L1 swap-in → 채널 자동 생성 + 50M sat 자동 funding
2. ✅ 양측 채널 보유 시 Bolt12 송금
3. ✅ **채널 없는 폰에게 Bolt12 송금 → OTF 자동 채널 생성**
4. ✅ 외부 L1 주소로 송금 (splice-out)
5. ✅ 지정 주소 mutual close
6. ✅ Force close + 144 블록 CSV 회수
7. ✅ Request Liquidity (splice-in, channel reserve BYPASS)

## 11개 핵심 패치 (vs upstream)

### `Setup.scala` (2곳)
1. `assert(initialBlockDownload && verificationProgress > 0.999 && headers == blocks)` 3개 우회 — BEC chain cumulative work 부족
2. Fee provider 매핑: non-regtest/non-signet chainHash는 `BitcoinCoreFeeProvider + ConstantFeeProvider` fallback

### `io/Peer.scala` (1곳)
3. Unknown message tag `35017` (Phoenix FCMToken) silently 수락

### `transactions/Transactions.scala` (3 검증 BYPASS)
4. `checkRemoteSig` 항상 true
5. `checkRemotePartialSignature` 항상 true (musig2)
6. `checkRemoteHtlcSig` 항상 true

> **운영 시 제거 필수**. dev/test 전용.

### `channel/fund/InteractiveTxBuilder.scala` (3곳)
7. **musig2 nonce 정렬** — splice tx partial sign (line ~116)
   ```scala
   val sortedFundingKeys = Scripts.sort(Seq(localFundingKey.publicKey, remoteFundingPubkey))
   val orderedNonces = if (sortedFundingKeys.head == localFundingKey.publicKey)
     Seq(localNonce.publicNonce, remoteNonce) else Seq(remoteNonce, localNonce.publicNonce)
   ```
8. **musig2 nonce 정렬** — 새 채널 첫 commit tx (line ~1000)
9. **channel reserve BYPASS** — splice 시 폰 잔액 < 1% reserve 허용 (line ~865)
   - 이 패치가 L1 송금 + Request Liquidity 양쪽 모두 해결의 핵심

### `channel/fsm/ChannelOpenSingleFunded.scala` (2곳 + import)
10. musig2 nonce 정렬 — line ~230, ~306
11. `import fr.acinq.eclair.transactions.{Scripts, Transactions}` 추가 필수

### `channel/Helpers.scala` (5곳)
12. musig2 nonce 정렬 — 3개 closing tx (line ~830, ~886, ~966)
13. `validateParamsDualFundedNonInitiator` — `usesOnTheFlyFunding` 시 fundingAmount=0 허용 (line ~169)
14. `checkCommitNonces` — Taproot 채널 `MissingCommitNonce` 무시 (line ~593)

### `payment/relay/NodeRelay.scala` (3패치)
15. `walletNodeId_opt = Some(recipient.nodeId)` 강제 (line ~315)
16. `shouldAttemptOnTheFlyFunding` recipientFeatures=None 우회 (line ~158)
17. **`BlindedPathsResolver.PartialBlindedRoute` 케이스 추가** (line ~289) — Bolt12 채널 없는 수신자 OTF 트리거 핵심

### `resources/reference.conf`
18. `to-remote-delay-blocks = 144` (기본 720)

## 런타임 설정 (`eclair.conf`)

```hocon
eclair.chain = "mainnet"
eclair.bitcoind.host = "10.8.0.6"
eclair.bitcoind.rpcport = 8334
eclair.api.password = "bitever"

# OTF 채널 생성 필수: paymentTypes 확장
eclair.liquidity-ads.payment-types = [
  "from_channel_balance",
  "from_channel_balance_for_future_htlc",
  "from_future_htlc",
  "from_future_htlc_with_preimage"
]

# Funding rates (1 sat ~ 100M sat, 무료)
eclair.liquidity-ads.funding-rates = [{
  min-funding-amount-satoshis = 1
  max-funding-amount-satoshis = 100000000
  funding-weight = 400
  fee-base-satoshis = 0
  fee-basis-points = 0
  channel-creation-fee-satoshis = 0
}]

# 폰 노드 화이트리스트 (50M auto-funding)
channel-funding {
  remote-node-requirements {
    peer-whitelist = ["YOUR_PHONE_NODEID_HERE", ...]
  }
  dual-funding-liquidity-policy {
    peer-whitelist = ["..."]
    local-funding-amount-sat = 5000000
  }
}

# Trampoline + 0 fee 라우팅
eclair.trampoline-payments-enable = true
eclair.relay.fees.min-trampoline { fee-base-msat = 0, fee-proportional-millionths = 0 }
```

## 빌드

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true
# 산출: eclair-core/target/eclair-core_2.13-0.13.1.jar
```

기존 dist 폴더 `lib/eclair-core_2.13-0.13.1.jar`을 새로 빌드된 JAR로 덮어쓰기.

## 실행

```bash
cd <DIST>/eclair-node-0.13.1-XXXX
java -cp "lib/*" fr.acinq.eclair.Boot /root/.eclair/plugins/channel-funding-plugin-0.13.1.jar
```

**플러그인 JAR 인자 필수**. 빠지면 자동 채널 생성 abort.

## 1회 셋업: OTF 활성화

재시작 후 매번 한 번 호출:
```bash
curl -s -u :PASSWORD -X POST http://localhost:8080/enablefromfuturehtlc
# {"enabled": true}
```

## 동작 검증

| 기능 | 로그 패턴 |
| --- | --- |
| 자동 채널 생성 | `accepting a new channel with type=phoenix_simple_taproot_channel` |
| Bolt12 OTF | `forwarding payment to blinded recipient X with walletNodeId=Y (BitEver DEV-BYPASS)` |
| splice 성공 | `splice tx created with fundingTxIndex=N` |
| funding confirm | `setting localFundingStatus=ConfirmedFundingTx` |
| reserve BYPASS 동작 | `[DEV-BYPASS] peer below channel reserve ... ignoring` |
| 검증 BYPASS 동작 | `[Musig2] verify result=true (...) => BYPASS=true` |

## 상세 가이드

- 아키텍처: `LightningEver_Architecture.md`
- 완전 재구현 가이드: `260517_LN_FIN.md`

## 보안 경고

이 빌드는 BitEver test chain 전용입니다. 위의 검증 BYPASS 8개 때문에 실제 mainnet에서 사용하면 자금 손실 위험. 운영 배포 전 모든 BYPASS 제거 + strict 검증 활성화 필수.
