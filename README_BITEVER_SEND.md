# BitEver Eclair — 260517_SEND 브랜치

이 브랜치는 ACINQ Eclair v0.13.1을 BitEver L1 체인용 LSP로 fork한 빌드입니다. 5월 17일까지 검증된 5가지 마일스톤을 모두 지원합니다.

## 달성 마일스톤

1. ✅ 외부 L1 입금 → 자동 채널 생성 (~52M sat)
2. ✅ Bolt12 송금 (양측 채널 보유)
3. ✅ 지정 주소 mutual close
4. ✅ Force close + 144 블록 CSV 회수
5. ✅ **채널 없는 폰에게 Bolt12 송금 → 자동 채널 생성** (이 브랜치 신규)

## 주요 변경점 (vs upstream)

### `channel/Helpers.scala`

- **musig2 nonce 정렬** (3곳, closing tx partial-sign): `Scripts.sort()` 결과에 맞춰 nonce 순서 일치시킴. 미적용 시 close tx 검증 실패.
- **`validateParamsDualFundedNonInitiator`**: `usesOnTheFlyFunding`일 때 `fundingAmount=0` 허용. 채널 없는 폰이 LSP 전체 funding 요청 시 필수.

### `channel/fsm/ChannelOpenSingleFunded.scala`

- musig2 nonce 정렬 (2곳, 230/306라인) + `import fr.acinq.eclair.transactions.{Scripts, Transactions}` 추가.

### `channel/fund/InteractiveTxBuilder.scala`

- musig2 nonce 정렬 (2곳, splice + 첫 commit tx). **splice 측 미적용 시 request liquidity 즉시 실패**.

### `payment/relay/NodeRelay.scala`

3개 BitEver DEV-BYPASS 패치:
1. `walletNodeId_opt = Some(recipient.nodeId)` 강제 (wake_up feature cache 우회)
2. `shouldAttemptOnTheFlyFunding` recipientFeatures=None 우회
3. `BlindedPathsResolver.PartialBlindedRoute(WithPublicKey, _, _)` 케이스 추가 — **Bolt12 채널없는 수신자 OTF 트리거 필수**

### `resources/reference.conf`

- `to-remote-delay-blocks = 144` (기본 720)

### eclair.conf (런타임)

```hocon
eclair.liquidity-ads.payment-types = [
  "from_channel_balance",
  "from_channel_balance_for_future_htlc",
  "from_future_htlc",
  "from_future_htlc_with_preimage"
]
```

OTF 채널 생성 시 폰 B가 fee를 미래 HTLC에서 차감하려면 `from_future_htlc` 광고 필수.

## 빌드

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true
```

산출: `eclair-core/target/eclair-core_2.13-0.13.1.jar`

배포 시 기존 dist 폴더의 `lib/eclair-core_2.13-0.13.1.jar`을 덮어쓰면 됨.

## 실행

```bash
cd <DIST>/eclair-node-0.13.1-XXXX
java -cp "lib/*" fr.acinq.eclair.Boot /root/.eclair/plugins/channel-funding-plugin-0.13.1.jar
```

플러그인 JAR 인자가 빠지면 자동 채널 생성이 abort됨.

## OTF 활성화 (1회만)

```bash
curl -s -u :PASSWORD -X POST http://localhost:8080/enablefromfuturehtlc
# → {"enabled": true}
```

## 동작 검증

| 기능 | 로그에서 확인 |
| --- | --- |
| 채널 자동 생성 | `accepting a new channel with type=phoenix_simple_taproot_channel` |
| Bolt12 송금 | `forwarding payment to blinded recipient ... with walletNodeId=... (BitEver DEV-BYPASS)` |
| OTF 채널 생성 | `on-the-fly funding proposed` → `OUT msg=WillAddHtlc(...)` → `IN msg=OpenDualFundedChannel(...)` |

## 상세 가이드

전체 재구현 가이드: `260517_LN_4.md` (이 레포에는 미포함, 별도 위치).

## 알려진 미해결 이슈

- 외부 L1 송금 시 `Aborted by peer [previous tx missing from tx_add_input (serial_id=000000000000)]`
- 지정 mutual close 일부 시나리오에서 negotiating 상태 정지
- 폰 A request liquidity 항상 실패 (폰 B는 가끔 성공)

세 이슈 모두 `previous tx missing from tx_add_input` 패턴 공유. interactive-tx 협상 시 LSP의 `tx_add_input` 페이로드 누락 추정. LN_5에서 해결 예정.
