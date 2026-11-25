# Analytics (Mixpanel)

Telemetria para rastrear em produção por que a conexão com o leitor falha ou cai,
e por que o envio ao backend para. Antes disso só existia `Log.e` no aparelho.

- **Projeto:** o mesmo do app de campo (`spacevis-mobile`). Filtre por `app = uhf_logger`.
- **Token:** `BuildConfig.MIXPANEL_TOKEN`, só no flavor `prd`. É o token público de
  cliente, que vai dentro de todo APK e não é segredo. No `hml` fica vazio e a
  fachada não faz nada: nada de homologação vai para o projeto de produção.
- **Código:** `analytics/Analytics.kt` (fachada, envio) e
  `analytics/ConnectionTelemetry.kt` (nomes e propriedades, lógica pura e testada).
- **Garantias:** `track()` nunca lança exceção e nunca bloqueia, porque tudo roda numa
  thread própria. Os eventos ficam na fila offline do SDK e saem quando houver rede.
  A instrumentação não muda retries, timeouts nem threads da conexão.

## Super properties (em todo evento)

| Propriedade | Origem |
|---|---|
| `app` | sempre `uhf_logger` |
| `app_version` / `app_build` | `versionName` / `versionCode` (ex.: `1.4.0-b7` / `107`) |
| `device_model` / `device_manufacturer` / `os_version` | `Build.MODEL` / `Build.MANUFACTURER` / `Build.VERSION.RELEASE` |
| `environment` | flavor: `prd` (o `hml` não envia) |
| `device_id` | `DeviceIdentity.readerId()`, o mesmo `reader_id` que vai ao backend. Também é o `distinct_id`. |

## Eventos

`transport` é sempre `bluetooth` ou `usb`. `attempt` vale `0` em início manual ou
retomada após restart do processo, e `n` na n-ésima tentativa do loop de reconexão.
`error_message` vem em uma linha, cortada em 160 caracteres.

### `App Started`
Um evento por início do **processo** (`UHFLoggerApplication.onCreate`), com a tela aberta ou
não (WorkManager, boot e serviço religado pelo Android também contam). Serve para ver a frota:
quem já está na versão nova (`app_version`) e se o aparelho está pronto para a Smartbox.

| Propriedade | |
|---|---|
| `smartbox_key_configured` | o build tem a chave K |
| `farm_selected` / `farm_id` | fazenda selecionada no Servidor SpaceVis |
| `backend_enabled` | envio do CSV ao servidor ligado |
| `smartbox_auto_collect` | preferência da coleta automática (padrão: ligada) |
| `bt_connect_granted` | permissão "Dispositivos próximos" concedida |
| `antenna_type` | antena configurada |
| `was_capturing` | o processo subiu com uma captura em andamento (restart/morte do processo) |

A coleta automática só roda com chave + fazenda + permissão + preferência ligada. Um
`App Started` com algum desses `false` explica uma smartbox que não está sendo baixada.

### `Reader Connection Opened`
A conexão ficou de pé (probe OK e captura iniciada).
Disparado em `startBtConnection` e `startUsbConnection`.

| Propriedade | |
|---|---|
| `transport`, `attempt` | |
| `duration_ms` | do `startCapture()` até a captura começar (no BT inclui o retry local do probe) |
| `resuming` | `true` = retomando uma sessão pausada por queda |
| `device_name` | nome do leitor BT (`SPACEVIS_RFID_…`) ou caminho USB |

### `Reader Connection Failed`
A tentativa de abrir não chegou ao fim.

| `step` | Onde | Transporte |
|---|---|---|
| `permission` | `BLUETOOTH_CONNECT` não concedida | BT |
| `adapter` | aparelho sem adaptador BT | BT |
| `device_lookup` | leitor fora dos pareados, `SecurityException` lendo os pareados, ou USB não enumerado | BT/USB |
| `usb_permission` | `openDevice()` devolveu null | USB |
| `socket_connect` | `BluetoothSocket.connect()` falhou | BT |
| `usb_open` | `open()`/`setParameters()` da porta falhou | USB |
| `probe` | a porta abriu mas o módulo não respondeu (no BT, só depois do retry local). RoyalRay: `GET_INFO` inválido ou sem resposta (`error_message` = `royalray get_info: <motivo>`), ou RoyalRay com USB | BT/USB |
| `unexpected` | exceção fora dos passos acima | BT |

Outras propriedades: `transport`, `error_name`, `error_message`, `attempt`,
`duration_ms` e `bt_enabled` (só no BT; null quando não dá para saber).

### `Reader Connection Dropped`
Uma conexão aberta caiu sem o app pedir. Disparado em `handleRunError`, só depois
do `compareAndSet`: o Parar do usuário baixa `isRunning` antes de fechar a porta,
então não conta como queda.

| `reason` | |
|---|---|
| `read_error` | a leitura do socket/USB lançou (BT caiu, cabo desplugado) |
| `watchdog_silence` | o watchdog fechou a porta depois de sondas sem resposta (conexão zumbi) |
| `config_error` | falhou a configuração do Winnix logo depois de abrir; RoyalRay: comando sem resposta na sequência SET/START |

Outras propriedades: `transport`, `error_name`, `error_message` e `open_ms` (quanto
tempo ficou aberta).

### `Reader Reconnect Loop`
O loop de reconexão (`startBtReconnectLoop` / `startUsbReconnectLoop`) terminou.
O loop não desiste sozinho: ou reconecta, ou é parado (Parar, salvar após erro,
serviço destruído).

| Propriedade | |
|---|---|
| `transport` | |
| `outcome` | `reconnected` \| `cancelled` |
| `attempts` | quantas voltas o loop deu |
| `total_ms` | duração do loop |

### `Upload Failed`
Uma etapa do `BackendUploadWorker` falhou. Sai um evento por passada do worker,
então com o aparelho offline cada retry gera um evento com `status = 0`.

| Propriedade | |
|---|---|
| `step` | `open_capture` \| `send_batch` \| `close_capture` \| `exception` |
| `status` | HTTP status; `0` = sem conexão; ausente quando `step = exception` |
| `error_name`, `error_message` | só quando `step = exception` |

### Smartbox (bebedouro)

Os eventos e as propriedades seguem os do spacevis-mobile, com dois acréscimos em todo
evento de sessão: `reader_state` (`idle` | `bt_capturing` | `bt_reconnecting` |
`usb_capturing` | `usb_reconnecting`) e `collect_mode` (`blocking` | `background` |
`manual`). Código: `smartbox/ble/SmartboxTelemetry.kt`.

| Evento | Quando | Propriedades principais |
|---|---|---|
| `Smartbox Session Opened` | sessão autenticada | `module_id`, `purpose`, `connect_attempts`, `duration_ms`, `mtu`, `rssi_dbm`, `mode`, `adv_rssi_dbm`, `adv_age_ms` |
| `Smartbox Session Failed` | abertura ou ação falhou | `step` (`precheck`, `connect`, `mtu`, `discover`, `notify`, `handshake`, `action`), `android_error_code` (status GATT do callback, ou lido do texto do erro), `attempt_errors`, `error_name`, `error_message` |
| `Smartbox Session Dropped` | caiu depois de aberta, sem o app pedir | `reason`, `open_ms` |
| `Smartbox Scan Failed` | `onScanFailed` do Android (o app tenta de novo em 5 s) | `ble_error_code`, `error_message`, `scan_open_ms` |
| `Smartbox Collect Yielded` | a smartbox cedeu o rádio para a captura | `trigger` (`start_capture` \| `stop_button`), `downloaded`, `remaining`, `waited_ms`, `forced_disconnect` |

## Consultas úteis

- Taxa de falha por passo: `Reader Connection Failed`, quebrado por `step` e `transport`.
- Aparelho problemático: qualquer evento quebrado por `device_id`, para cruzar com
  o `reader_id` no backend.
- Tempo até reconectar: `Reader Reconnect Loop` com `outcome = reconnected`,
  mediana de `total_ms`.
