# LightningEver Eclair LSP (BitEver) - SAVE2_fee Branch

## 개요
BitEver Lightning Service Provider. Taproot Musig2 서명 검증을 개발/테스트 목적으로 우회하여 채널 안정성 확보.

## 클론 및 빌드

```bash
git clone -b SAVE2_fee https://github.com/makewalletfirst/LightningEver-bitever-eclair.git
cd LightningEver-bitever-eclair

# 빌드 (약 10분)
./mvnw package -DskipTests -pl eclair-node -am

# JAR 배포
cp eclair-node/target/eclair-node-*.jar /path/to/eclair-dist/lib/
```

## 핵심 수정 파일

| 파일 | 수정 내용 |
|------|----------|
| `eclair-core/src/main/scala/fr/acinq/eclair/crypto/NonceGenerator.scala` | Musig2 key 순서를 KMP와 동일하게 (unsorted) |
| `eclair-core/src/main/scala/fr/acinq/eclair/channel/Commitments.scala` | HTLC sig 검증 bypass (DEV) |
| `eclair-core/src/main/scala/fr/acinq/eclair/channel/fsm/ErrorHandlers.scala` | invalid htlc sig 에러 수신 시 force-close 방지 |

## eclair.conf 필수 설정

```hocon
eclair.chain = "mainnet"
eclair.server.port = 9735
eclair.api.enabled = true
eclair.api.password = "bitever"
eclair.liquidity-ads.funding-rates = [{
  min-funding-amount-satoshis = 1, max-funding-amount-satoshis = 100000000
  funding-weight = 400, fee-base-satoshis = 0, fee-basis-points = 0
  channel-creation-fee-satoshis = 0
}]
```

## 실행

```bash
cd eclair-node-*/
./bin/eclair-node.sh

# 상태 확인
curl -s -u eclair-user:bitever -X POST http://localhost:8080/getinfo
```

## 원복 방법
```bash
git checkout SAVE2_fee
./mvnw package -DskipTests -pl eclair-node -am
cp eclair-node/target/eclair-node-*.jar /path/to/eclair-dist/lib/
# Eclair 재시작
```
