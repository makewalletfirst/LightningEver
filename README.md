# LightningEver-bitever-eclair (LSP Server)

BitEver (BEC) L1 체인 전용 라이트닝 네트워크 LSP(Liquidity Service Provider) 서버입니다.
ACINQ Eclair v0.13.1을 기반으로 비트에버 체인 특성과 Phoenix 포크 앱 호환성에 맞춰 커스텀 패치되었습니다.

## 🚀 주요 구현 사항 (260505 ~ 260507)
- **BitEver 체인 파라미터 적용:** Magic Bytes (`0xe2c3a9fc`), Genesis Hash, Bech32 HRP 등 적용.
- **연결 유지 패치 (Forced Sync):** 비트코인 메인넷과 다른 체인워크 환경에서도 피어 연결이 끊기지 않도록 `PeerConnection` 로직 수정.
- **수수료 정책 최적화:** 비트에버 노드의 `estimatesmartfee` 실패를 회피하기 위해 `default-feerates`를 강제 적용.
- **피닉스 앱 호환성:** `ChannelFundingPlugin`을 통한 피닉스 전용 스왑 프로토콜(35017) 지원.
- **Splice TLV 확장 (260505):** `InteractiveTxTlv.scala`의 `SwapInUserPartialSigs`/`SwapInServerPartialSigs`가 partial sig + nonces를 동시에 운반하도록 변경.
- **Splice 검증 우회 (260506 / 260507):** `Channel.scala` line 1100의 `DualFundedUnconfirmedFundingTx` 검증을 비활성화. 비트코인 노드 ZMQ pub의 산발적 누락으로 컨펌이 LSP에 늦게/누락되어 도달하더라도 다음 splice 요청을 거부하지 않도록 함. **앱→L1 splice-out을 유동성 한도 내에서 무제한 반복 가능하게 하는 핵심 패치.**

> 📄 **세션 기록 / 재현 가이드**: `260506SEND.md`, `260507_SENDCOMPLETE.md` (별도 저장소 또는 운영 머신 `/root/md/`에 보관).

## 🧠 패치 위치 한 눈에 보기

| 파일 | 라인 | 변경 |
|---|---|---|
| `eclair-core/src/main/scala/fr/acinq/eclair/wire/protocol/InteractiveTxTlv.scala` | 126~ | `SwapInPartialSignature` 추가, swap-in TLV가 nonces 운반 |
| `eclair-core/src/main/scala/fr/acinq/eclair/channel/fsm/Channel.scala` | 1100 | `DualFundedUnconfirmedFundingTx` 검증을 `false &&`로 우회 (test chain 한정) |

## 🛠 기술 스택
- **언어:** Scala 2.13
- **프레임워크:** Akka, Akka HTTP
- **빌드 도구:** Maven 3.x
- **데이터베이스:** SQLite
- **자바 버전:** OpenJDK 21

## 🏗 빌드 및 실행 방법

### 사전 요구사항
- 비트에버 L1 노드 실행 중 (RPC + ZMQ 활성화)
- Java 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
- Maven 3.x

### 빌드 (전체)
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
mvn clean install -DskipTests
```

### 빌드 (eclair-core만 — 빠름, ~25초)
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
mvn install -pl eclair-core -am -DskipTests \
    -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -B
```
빌드 결과: `eclair-core/target/eclair-core_2.13-0.13.1.jar`

### 운영 노드(`/root/bitever-eclair-dist/eclair-node-0.13.1-7d8fa7a`)에 패치 jar 배포
```bash
DIST_LIB=/root/bitever-eclair-dist/eclair-node-0.13.1-7d8fa7a/lib

# 1) 기존 jar 백업
cp $DIST_LIB/eclair-core_2.13-0.13.1.jar \
   $DIST_LIB/eclair-core_2.13-0.13.1.jar.bak.$(date +%s)

# 2) 새 jar 교체
cp eclair-core/target/eclair-core_2.13-0.13.1.jar $DIST_LIB/

# 3) 노드 재시작
pkill -f 'eclair-node-0.13.1-7d8fa7a' && sleep 6
cd /root/bitever-eclair-dist/eclair-node-0.13.1-7d8fa7a/bin
nohup ./eclair-node.sh /root/.eclair/plugins/channel-funding-plugin-0.13.1.jar \
      >> ~/.eclair/eclair.log 2>&1 & disown
```

### 가동 확인
```bash
# API 헬스체크 (~15초 후)
curl -s -u :bitever -X POST http://localhost:8080/getinfo | python3 -m json.tool

# 채널 / 피어 / 단일 채널 상세
curl -s -u :bitever -X POST http://localhost:8080/peers   | python3 -m json.tool
curl -s -u :bitever -X POST http://localhost:8080/channels | python3 -m json.tool
curl -s -u :bitever -X POST -d "channelId=$CID" http://localhost:8080/channel | python3 -m json.tool
```

### Splice / 운영 모니터링
```bash
tail -F ~/.eclair/eclair.log | grep -E \
  'SpliceInit|SpliceAck|SpliceLocked|TxSignatures|TxAbort|InvalidSplice|ZmqWatcher|RecommendedFeerates'
```

## ⚙️ 운영 환경 설정 (`~/.eclair/eclair.conf` 핵심 값)
```hocon
eclair.chain = "mainnet"
eclair.server.port = 9735
eclair.api.enabled = true
eclair.api.password = "bitever"
eclair.api.port = 8080

eclair.bitcoind.host = "10.8.0.6"
eclair.bitcoind.rpcport = 8334
eclair.bitcoind.zmqblock = "tcp://10.8.0.6:28332"
eclair.bitcoind.zmqtx    = "tcp://10.8.0.6:28333"

# splice 수수료 하한선(병목 1) 우회
eclair.on-chain-fees.default-feerates {
  minimum = 1
  slow    = 1
  medium  = 1
  fast    = 1
  fastest = 1
}
eclair.on-chain-fees.min-feerate = 1

# Phoenix 호환 features
eclair.features {
  option_static_remotekey = mandatory
  option_dual_fund        = mandatory
  option_simple_taproot_phoenix = optional
  option_simple_close     = optional
  ...
}
```

## 📡 Send (Splice-out) 워크플로우 요약
1. LSP가 `/open`으로 5,000,000 sat dual-fund 채널 단독 개설 (앱 contribution=0).
2. 앱 발행 amountless 인보이스를 LSP가 `payinvoice`로 결제 → 1홉으로 잔고 푸시.
3. 사용자가 앱 Send 버튼 → 앱이 splice-out (외부 L1 주소로 송금) 시도.
4. LSP가 SpliceAck → InteractiveTx 협상 → CommitSig/TxSignatures 교환 → splice tx broadcast.
5. **이 README가 적용된 jar로는 (3)~(4) 흐름이 ZMQ 누락에 영향받지 않고 반복 가능.**

## ⚠️ 주의 — 운영 안전 메모
- 이 패치는 **비트에버 테스트 체인 환경 전용** 입니다. mainnet 운영 시 `Channel.scala` line 1100 우회는 RBF 메커니즘과 충돌 위험이 있습니다.
- ZMQ pub의 산발적 누락은 비트코인 노드(`10.8.0.6:28332/28333`) 측 안정화가 정공법입니다. 이 패치는 LSP가 그 영향을 덜 받게 하는 보호막입니다.

## ⚙️ CI/CD (GitHub Actions)
`.github/workflows/build.yml` 파일을 통해 코드 변경 시 자동으로 Maven 빌드 및 유효성 검사를 수행합니다.

## 🌿 브랜치 정책
- `main` — upstream merge 또는 안정 릴리스 후보
- `260505` — 초기 BitEver 체인 / Phoenix 호환 패치
- `260506` — Send (splice-out) 1차 성공 시점 스냅샷 + 절차 문서
- `260507` — `Channel.scala` 우회 패치 + 무제한 반복 send + **close/force-close 진단 검증 완료**

## 🆕 260506 → 260507 변화 요약
LSP(eclair) 측 코드는 260506 패치 이후 추가 변경 없음 (`Channel.scala` 1100 우회 그대로 유지). 본 브랜치의 의의는:
- **LSP `/close` · `/forceclose` 정상 동작 검증 완료**
  - cooperative close: `OUT msg=Shutdown(with ShutdownNonce TLV)` 정상 발신.
  - force-close: `commit-tx` + anchor + main-delayed publish 정상.
- **앱 close 무동작 진단 결과 LSP 코드 무결**로 결론. 실제 수정은 `LightningEver-bitever-eclair-kmp` 의 `260507` 브랜치 (Phoenix 앱 라이브러리)에서 진행.
- 운영 절차/재현 가이드는 `260507_CLOSECOMPLETE.md` 단일 문서로 통합.

## 📜 운영 가이드 / 재현 절차
이 LSP를 처음부터 띄우고, 채널 개설·유동성 주입·send·close까지 단계별로 따라할 수 있는 통합 가이드는 별도 문서 **`260507_CLOSECOMPLETE.md`**(운영 머신 `/root/md/`) 참조.
