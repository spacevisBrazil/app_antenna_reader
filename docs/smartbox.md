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
| 2 | Conexão GATT, busca, coordenação do rádio com o leitor, tela Buscar, telemetria | #20 |
| 3 | Banco local, coleta automática, envio ao backend, tela de progresso | #21 |
| 4 | Diagnóstico e Reiniciar | este |
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

## Coleta automática e envio (PR 3)

**Zero passo para o operador.** A coleta já vem ligada sempre que o build tem a chave K, o
aparelho tem fazenda selecionada e a permissão de Bluetooth foi dada. O ideal é a permissão
vir concedida pelo MDM. Sem qualquer um desses três, não sobe nada (nem serviço nem scan) e a
tela principal fica igual a antes (`AutoCollectRules.shouldRun`). Só o técnico desliga, em
Configurações → Smartbox → Coleta automática.

| Peça | O quê |
|---|---|
| `collect/SmartboxCollectService.kt` | serviço em primeiro plano `connectedDevice`, com canal, notificação (4201) e ID próprios; convive com o `UHFReaderService`. Sobe no `MainActivity.onStart` com o app na frente. Se o Android recusar, para quieto e não derruba nada |
| `collect/SmartboxCollector.kt` | laço da seção 7: procura → conecta → baixa → desconecta → respiro de 2 s. Cada sessão roda num Job filho (o corte do yield não mata o laço) e cede o rádio às telas de técnico (`hold`) |
| `collect/AutoCollectRules.kt` | candidatas e pausas (§7.2/7.3), regra de ligar e regra do aviso de 24 h. Puro e testado |
| `data/SmartboxDatabase.kt` | Room próprio (`smartbox.db`), separado do banco que já está em campo. Chave única (módulo, epc, porta, instante) + INSERT OR IGNORE; lote numa transação; pendente nunca é apagada; enviada some depois de 7 dias |
| `sync/SmartboxUploader.kt` | §9.3: lotes de 250 e a classificação do firmware. Só sobe leitura de smartbox que anunciava a fazenda do aparelho. Puro e testado |
| `sync/SmartboxUploadWorker.kt` | WorkManager: só com rede, um por vez, retry com backoff. Disparado no fim de cada descarga e quando a coleta liga |
| `ui/SmartboxCollectActivity.kt` | tela de progresso que trava a navegação (leitor parado) |

### O que o operador vê

| Leitor | Tela principal |
|---|---|
| Captura por Bluetooth | linha discreta "Smartbox: aguardando a leitura parar" |
| Captura por USB | linha "Smartbox SPV-…: baixando N de M" + notificação com progresso |
| Parado | tela de progresso: nome, barra, "N de M leituras", tempo estimado e um único botão, **Parar e ler brincos**. No fim, "Pronto: N leituras guardadas, enviando ao servidor" por ~3 s, e ela fecha sozinha |

- **Parar e ler brincos** não tem lógica própria: faz o mesmo que **Iniciar leitura**
  (`startCaptureYieldingSmartbox`), ou seja, a smartbox termina o lote em mãos, desconecta, e a
  captura começa.
- **Falhas** (fora de alcance, conexão caiu) são silenciosas: o app tenta de novo com as pausas
  do §7 e registra só na telemetria.
- **Aviso amarelo** quando uma smartbox da fazenda está por perto (vista na última hora) há
  24 h com leituras sem conseguir baixar: "A smartbox X não está sendo baixada. Avise o
  suporte."

## Diagnóstico (PR 4)

Na tela de técnico Buscar, toque em **Diagnóstico** numa smartbox desta fazenda ou sem
fazenda. A tela faz uma conexão curta (conecta, `GET_DIAG`, desconecta) e mostra:

- **Cabeçalho**, com dados do CHALLENGE: firmware, protocolo, MAC, modo (e o motivo, se em
  configuração), pendentes e a fazenda pelo nome.
- **Estado interno** legível, com as cores da seção 13.2 (`domain/DiagFormat.kt`, puro e
  testado): relógio comparado com o aparelho no instante da leitura, antenas juntas e em ordem
  (antena ausente nunca vira 0), temperatura numa linha só. Também dá para ver o **texto cru**.
- **Reiniciar smartbox**, com confirmação e uma conexão nova. Só aparece em caixa minha ou sem
  fazenda, segundo o CHALLENGE.
- **Padrão de fábrica** não existe no UHF Logger: só o gestor faz, no SpaceVis Field.

As ações manuais usam `waitIfBusy`: se a coleta automática estiver terminando um lote, a ação
espera em vez de falhar.
