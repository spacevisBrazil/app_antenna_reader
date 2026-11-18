# Smartbox (bebedouro)

Integração BLE com a smartbox do bebedouro: o app procura as smartboxes da
fazenda, baixa as leituras guardadas e envia ao backend. A especificação completa
está no **guia de implementação da smartbox**, que é confidencial e não fica neste
repositório porque contém a chave K. A referência executável é
`spacevis-mobile/src/features/smartbox`.

Entrega em PRs pequenos:

| PR | Conteúdo | Estado |
|---|---|---|
| 1 | Protocolo, cripto, framing, sessão e descarga (JVM, contra simulador) + chave K pelo build | #19 |
| 2 | Conexão GATT, busca, coordenação do rádio com o leitor, tela Buscar, telemetria | este |
| 3 | Banco local, coleta automática, envio ao backend, tela de progresso | — |
| 4 | Diagnóstico e Reiniciar | — |
| 5 | Configurar | — |

## Chave K (confidencial)

São 32 bytes, iguais em todos os firmwares de produção e em todos os apps. A chave
vai dentro do APK de qualquer jeito, então o cuidado é **não espalhá-la**:

- **Nunca** no Git, em log, em teste, em issue, em chat ou em PR.
- Os testes usam só a chave **de bancada** do Anexo A (`00 01 … 1F`, pública) e chaves
  falsas. O app recusa a de bancada em produção.

Como ela entra no build (`build.gradle`), na ordem de prioridade:

1. `smartbox.bleKey=<64 hex>` no `local.properties` (ignorado pelo git): dev local.
2. `-PSMARTBOX_BLE_KEY=<64 hex>` na linha de comando.
3. Variável de ambiente `SMARTBOX_BLE_KEY`. No CI vem do secret do GitHub Actions
   de mesmo nome (`build-and-deploy.yml`).

A mesma chave vale para hml e prd, porque ela é do firmware e não do backend. Se a
chave faltar ou vier fora do formato (64 hex), o build **não falha**: sai um APK com a
Smartbox desligada, que mostra *"Chave da smartbox não configurada neste build. Avise o
suporte."*. O resto do app não muda. O Gradle só avisa que a chave está ausente e
nunca imprime o valor.

## Código (`com.uhflogger.smartbox.ble`)

| Arquivo | O quê |
|---|---|
| `Constants.kt` | UUIDs, opcodes, status, tamanhos e prazos: o contrato com o firmware |
| `Bytes.kt` | little-endian, hex, comparação em tempo constante |
| `Protocol.kt` | anúncio, CHALLENGE, respostas, LOTE/ACK, SET_FARM, WIFI_SCAN (puro) |
| `Crypto.kt` | HMAC-SHA256 e AES-128-GCM (`javax.crypto`), AUTH/AUTH_OK, Ks |
| `Framing.kt` | partes de `[cabeçalho 1 B][pedaço]` sobre o GATT |
| `Link.kt` | troca de mensagens sobre um `GattPort`; qualquer erro quebra o link de vez |
| `Session.kt` | handshake e chamadas cifradas, uma por vez |
| `Download.kt` | LOTE/ACK com as regras de ouro: commit antes do ACK; para só entre lotes |
| `FieldKey.kt` | lê e valida a K do `BuildConfig` |

Os testes ficam em `src/test/.../smartbox/ble/`. O `FakeSmartbox` simula o lado do
firmware (handshake, sessão cifrada, LOTE/ACK) com a mesma cripto.

## Conexão e busca (PR 2)

| Arquivo | O quê |
|---|---|
| `ble/GattClient.kt` | `BluetoothGatt` nativo em coroutines: uma operação por vez, com timeout e status GATT |
| `ble/SmartboxConnection.kt` | trava global (uma sessão por vez; ocupado = falha na hora), sequência da seção 4.3, 3 tentativas, telemetria, `yieldForCapture` |
| `ble/SmartboxScanner.kt` | um scan só, com filtro de manufacturer data no hardware; `ScanScheduler` (puro) aplica 6 s entre inícios, vigia de 30 s e reabertura a cada 25 min |
| `radio/RadioPolicy.kt` | convivência com o leitor (abaixo) |
| `domain/FarmRules.kt` | ações por fazenda; `FarmLabel` cita a fazenda pelo nome |
| `sync/SmartboxApi.kt` | cadastro do módulo no backend com a credencial do aparelho |
| `ui/SmartboxSearchActivity.kt` | tela de técnico Buscar + Cadastrar (Configurações → Smartbox) |

### Prioridade da leitura de brincos

A leitura de brincos **sempre** tem prioridade. A regra fica em `RadioPolicy.decide`:

| Leitor | Smartbox |
|---|---|
| Captura por Bluetooth (inclusive conectando ou no loop de reconexão) | não busca nem conecta |
| Captura por USB | roda em segundo plano, sem tomar a tela |
| Parado | roda e pode mostrar a tela de progresso (PR 3) |

**Iniciar leitura com uma sessão de smartbox aberta:** `SmartboxConnection.yieldForCapture`
pede a parada normal, em que a descarga termina o lote em mãos (commit + ACK) e desconecta.
O teto é de 5 s. Se ele vencer, a sessão é cortada, mas **nunca entre o commit e o ACK**:
nesse caso a descarga espera o commit terminar e sai sem mandar o ACK (`DownloadControl`). O
lote volta na próxima conexão e o banco não duplica. Sem sessão aberta, a captura começa na
hora.

### Perfil e fazenda

- **Minha fazenda** é a selecionada no seletor do servidor SpaceVis. As outras aparecem
  pelo nome; uma fazenda desconhecida vira "outra fazenda (código N)".
- **Sem gestor:** o UHF Logger entra com a conta do aparelho, então Transferir, Padrão de
  fábrica e Tirar da fazenda ficam escondidos (`FarmRules.IS_MANAGER = false`). Essas
  ações são feitas no SpaceVis Field, com login de gestor.
