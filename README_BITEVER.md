# LightningEver-bitever-eclair (BitEver fork)

ACINQ Eclair의 BitEver 체인 전용 패치 fork. 원본 README는 `README.md` 참고.

## 브랜치

- **`260517`** — 2026-05-17 안정판. Phoenix Android (lightning-kmp 1.11.5-DEBUG) + 외부 plugin과 짝을 이루어 다음을 지원:
  1. 외부 L1 swap-in → 자동 채널 생성 (Phoenix initiator + Eclair plugin acceptor)
  2. Bolt12 trampoline 송금 (Phoenix A → LSP → Phoenix B)
  3. Mutual close (Phoenix가 지정 주소로 회수)
  4. Force close (Phoenix가 force-close, Eclair는 close 통지 수신)

## 주요 변경점 (vs upstream)

| 파일 | 변경 사유 |
| --- | --- |
| `eclair-core/src/main/scala/fr/acinq/eclair/channel/Commitments.scala` | (1) KMP의 `fromCommitSig`는 verificationNonce를 index `localCommitIndex`로 사용 (Eclair upstream은 `+1`을 더함). KMP와 호환 위해 `+1` 제거. (2) Taproot 채널의 HTLC sig count mismatch가 발생하는 키 derivation 차이를 우회 (DEV-BYPASS, 테스트 모드). (3) `sendCommit` musig2 partial sign에서 `Scripts.sort()`로 funding key 정렬 후 nonces 순서 동기화. |
| `eclair-core/src/main/scala/fr/acinq/eclair/channel/Helpers.scala` | `checkCommitNonces`에서 Taproot 채널의 `MissingCommitNonce`를 경고만 하고 None 반환 → 재연결 시 force-close 방지. |
| `eclair-core/src/main/scala/fr/acinq/eclair/channel/fsm/ErrorHandlers.scala` | (디버그 로그 보강) |
| `eclair-core/src/main/scala/fr/acinq/eclair/transactions/Transactions.scala` | Taproot HTLC sig 검증 우회 (위 DEV-BYPASS와 짝). |

상세 가이드: 동봉된 `260517_LN_3.md` 참고.

## 빌드법

```bash
git clone https://github.com/makewalletfirst/LightningEver-bitever-eclair.git
cd LightningEver-bitever-eclair
git checkout 260517

# core + node 모두 빌드
mvn -DskipTests install -pl eclair-node -am
# 산출물: eclair-node/target/eclair-node-*-bin.zip
```

빌드 시간: 약 5~10분 (clean 빌드).

## 외부 plugin (필수)

자동 채널 생성을 위해서는 `channel-funding-plugin`을 **반드시** 같이 실행해야 합니다:

- plugin 경로: `/root/eclair-plugins/channel-funding/` (별도 레포로 분리 가능)
- 빌드 후 JAR을 `~/.eclair/plugins/` 에 복사
- Eclair 시작 시 JAR을 **첫번째 인자**로 명시:

```bash
cd /path/to/eclair-bin/eclair-node-X.Y.Z
./bin/eclair-node.sh ~/.eclair/plugins/channel-funding-plugin-0.13.1.jar
```

plugin 없이 시작하면 모든 외부 swap-in이 abort 됩니다 (1 sat funding으로 거절).

## 구동법

### 1. eclair.conf 준비
```
~/.eclair/eclair.conf
```
주요 항목:
- `eclair.chain = "mainnet"` (자체 chainHash로 BitEver 사용)
- `eclair.bitcoind.host`, `rpcuser`, `rpcpassword`
- `eclair.node-alias = "BitEverLSP"`
- `eclair.api.password = "..."`

### 2. tmux 세션에서 실행
```bash
tmux new -s eclair
cd /root/eclair-bin/eclair-node-*
./bin/eclair-node.sh ~/.eclair/plugins/channel-funding-plugin-0.13.1.jar
```

부팅 로그에서 다음 확인:
- `channel-funding plugin loaded`
- `nodeId=0311fb42...`
- `chainHash=6fe28c0a...`
- `eclair API server started on port 8080`

### 3. 동작 확인
```bash
eclair-cli -p "비밀번호" -a 127.0.0.1:8080 getinfo
eclair-cli -p "비밀번호" -a 127.0.0.1:8080 channels
```

## 트러블슈팅

| 증상 | 해결 |
| --- | --- |
| 폰에서 swap-in → 채널 즉시 abort | plugin JAR 인자 누락. `eclair-node.sh JAR경로` 형태로 재실행 |
| 폰 재연결 후 force-close | `Helpers.scala` MissingCommitNonce 우회 패치 누락 (260517 브랜치 사용) |
| commit sig 검증 실패 | `Commitments.scala`의 nonce index/순서 패치 누락 |
| Bitcoin Core RPC 연결 실패 | eclair.conf의 bitcoind.host/port/credentials 확인 |

## 호환성

- 짝이 되는 lightning-kmp: 1.11.5-DEBUG (`LightningEver-bitever-eclair-kmp:260517`)
- 짝이 되는 Phoenix Android: `LightningEver-bitever-phoenix:260517`
- Bitcoin Core (BitEver fork) — 자체 chainHash

## 라이선스

Apache 2.0 (upstream Eclair와 동일)
