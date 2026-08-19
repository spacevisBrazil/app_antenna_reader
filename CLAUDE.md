# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin) that logs RFID/UHF tag reads from a **Winnix** UHF reader module (or a **Jietong** module), tags each read with GPS position/bearing, and exports sessions to CSV. Files are auto-synced to Google Drive. Designed to run for hours/days unattended in a vehicle (e.g. mounted on a tractor) with the screen off, so most core logic lives in a foreground `Service`, not the `Activity`.

The Winnix module connects either directly via USB, or via Bluetooth Classic (SPP) through an intermediary **ESP32** that bridges the module's UART to Bluetooth (`rfid_uhf_bluetooth_spp.ino`, not part of this repo/module — firmware lives separately). The Android app doesn't know or care about the ESP32 — to it, BT is just another `ISerialPort`.

Package: `com.uhflogger`. Module: `app` (single-module project).

## Knowledge graph — use graphify for all code questions

This project has a knowledge graph built with graphify, stored in `graphify-out/`. **Always use it:**

- **Any question about the project** (architecture, data flow, "where is X", "what calls Y") → run `/graphify query "<question>"` first before reading files manually.
- **Atualização do grafo** → NÃO rodar `/graphify --update` automaticamente após mudanças. Apenas lembrar o usuário ocasionalmente (a cada 3–5 commits ou ao final de uma sessão maior) se ele quer atualizar o grafo, e aguardar confirmação.
- `graphify-out/graph.html` — open in browser for the interactive visualization.
- `graphify-out/GRAPH_REPORT.md` — community map, god nodes, and suggested questions.

Key findings from the current graph (562 nodes, 1021 edges, 53 communities):
- `UHFReaderService` has 88 edges — the single most connected node, bridge between UI, CSV, BT, GPS, Drive, and Backend communities.
- `ISerialPort` is the serial abstraction hub connecting USB and BT transport to the rest.
- 3 hyperedges document the main pipelines: RFID Decoding Pipeline, CSV→Drive Sync Pipeline, and Process Death Auto-Resume.

## Development approach — use the `ponytail` skill

This project should be developed using the **ponytail** skill/mindset: the simplest, most minimal solution that actually works. Favor the standard library and existing patterns already in this codebase over new abstractions or dependencies. This app has a long history of subtle concurrency/lifecycle bugs (see "Project history" below) that came from extra state and special-casing — prefer removing complexity over adding it when touching `UHFReaderService`.

## Git

O repositório git está em `app/`, não na raiz do projeto. Sempre rodar comandos git de dentro de `app/`. Commits devem ser curtos e sem "Co-Authored-By: Claude":

```
cd app
git status
git add ...
git commit ...
```

### Dois remotes configurados

- `origin` → `https://github.com/daniel-spacevis/app_antenna_reader.git` (repositório pessoal — **padrão**)
- `team` → `https://github.com/spacevisBrazil/app_antenna_reader.git` (repositório da empresa)

**Regra:** todo commit/push vai para `origin` por padrão. Só fazer push para `team` quando o usuário pedir explicitamente ("commit no git da empresa", "sobe no git da empresa", etc.). Nunca fazer push para `team` automaticamente junto com o `origin`.

## Build / lint / test

Standard Gradle Android project — use the wrapper.

```
./gradlew assembleDebug          # build debug APK (uses hml flavor by default)
./gradlew installDebug            # build + install on connected device/emulator
./gradlew test                    # JVM unit tests (app/src/test)
./gradlew connectedAndroidTest     # instrumented tests on device (app/src/androidTest)
./gradlew lint                    # Android lint
```

On Windows use `gradlew.bat` instead of `./gradlew` from PowerShell.

### Product flavors (`hml` / `prd`)

There are two build flavors in `flavorDimensions "ambiente"`:

- **`hml`** — homologação. `API_BASE_URL` aponta para o servidor de staging. App se chama "UHF Logger HML". Adiciona sufixo `-hml` ao `versionName` (ex.: `1.1.2-hml`).
- **`prd`** — produção. `API_BASE_URL` aponta para o servidor de produção. App se chama "UHF Logger".

O `applicationId` é **o mesmo nos dois** de propósito: o OAuth Client ID do Google Drive é atrelado ao `package + SHA-1`, então um sufixo (`.hml`) quebraria o login do Drive no build de homologação. Consequência aceita: só um app por vez no aparelho. O ambiente (para onde os dados vão) é propriedade do artefato — não existe campo de servidor em nenhuma tela.

Para buildar um flavor específico:
```
./gradlew assembleHmlDebug   # homologação debug
./gradlew assemblePrdRelease # produção release (assinar manualmente)
```

There is currently no meaningful test suite (`ExampleUnitTest.kt` / `ExampleInstrumentedTest.kt` are the default Android Studio stubs) — don't assume test coverage exists for a change.

Kotlin code style is `official` (see `gradle.properties`).

## Architecture

### Two RFID antenna protocols, one pipeline

The app supports two physically different antenna families with separate wire protocols, unified behind a common `TagRecord` output:

- **Jietong** (USB only) — decoded by `decoder/ProtocolDecoder.kt`. Simple framed binary protocol (`0x43 0x4D` starter), read via `SerialInputOutputManager` directly.
- **Winnix** (USB or Bluetooth) — decoded by `decoder/WinnixProtocolDecoder.kt`. Requires a multi-step config handshake (antennas, power, working time, inventory mode) sent via `winnixConfigSequence()` before starting inventory, and supports start/stop temperature readout. Runs over either `SerialInputOutputManager` (USB) or `BluetoothInputOutputManager` (BT) — both funnel into the same `winnixOnNewData` handler.

Antenna type is a user setting (`SettingsManager.ANTENNA_TYPE_*`), not auto-detected — `probeAntennaType()` in `UHFReaderService` validates the configured type actually responds before starting capture, and fails with `onWrongAntennaType` if not.

The serial transport is abstracted behind `serial/ISerialPort` (`UsbSerialPortWrapper` vs `BluetoothSerialPort`), so protocol code doesn't need to know if it's talking to USB or BT.

### `UHFReaderService` is the real core of the app

`service/UHFReaderService.kt` (~2000 lines) owns almost everything: serial connection lifecycle, both decoders, GPS/compass sensor fusion, CSV session lifecycle, auto-save scheduling, and Drive upload triggering. `MainActivity` is intentionally thin — UI/permissions only, bound to the service via `LocalBinder`, driven by callbacks (`onStatusChanged`, `onCaptureError`, `onWrongAntennaType`, `onAutoSaved`, `onStopComplete`).

Key invariants encoded in comments in that file (read them before touching related code — several encode fixes for real field bugs):

- **GPS/compass/wakelock live in the Service, not the Activity**, on a dedicated `HandlerThread` (`locationSensorThread`), separate from the serial I/O thread and the UI thread — this was moved out of `MainActivity` because `onPause` (screen off) used to kill sensor registration mid-capture.
- **`isSessionActive` (Activity) vs `isCapturing`/`isPaused` (Service)** are deliberately different signals — `isSessionActive` stays true across automatic reconnect windows (BT/USB dropout) so the Settings menu stays locked; `capturing` flickers false during those reconnects and must not be used for that gate.
- **Automatic reconnection**: USB and Bluetooth each have an independent reconnect loop/executor (`btReconnectExecutor`, `usbReconnectExecutor`) so the app resumes on its own if the antenna drops — it does not rely solely on the `MainActivity`'s `usbReceiver`/`btReceiver`, since the Activity may not exist (screen off, process trimmed).
- **Silence watchdog** (`watchdogTick`, `lastDataReceivedAt`): detects a "zombie" connection (socket alive, no error, but no bytes arriving) by actively probing (`start_inventory`) before concluding it's dead — pure silence alone is not sufficient (a normal RFID-quiet stretch looks the same from outside).
- **`stopCapture(userInitiated: Boolean)`**: `userInitiated=false` is used from `onDestroy()` for process-death cleanup and deliberately does *not* clear the persisted "was capturing" flag, so `onCreate()` can auto-resume capture if Android kills and restarts the process. `userInitiated=true` (user pressed Stop) clears it.
- **WakeLock is renewed periodically with a timeout**, never held with an infinite `acquire()` — if the service ever hangs, the wakelock expires on its own instead of draining the battery forever.
- Foreground service type is resolved dynamically (`startForegroundWithSafeType`): only requests `FOREGROUND_SERVICE_TYPE_LOCATION` if location permission is already granted, since Android 14+ throws if you claim a type without the permission at the moment `startForeground()` is called.

### GPS/bearing fusion

Each tag read is stamped with the best available position/bearing at read time (`getCurrentLatitude/Longitude/Bearing/GnssSpeed`), not looked up later — see `applyLocation()` / `isBetterLocation()` for the fix-acceptance heuristic and the GNSS-vs-sensor-fusion bearing hysteresis (`bearingSourceIsGnss`, switches at `BEARING_SPEED_HIGH_MS`/`BEARING_SPEED_LOW_MS` to avoid flapping between sources near the threshold).

`isBetterLocation()` has the following acceptance rules in order:
1. Reject if fix is older than 30s.
2. Accept anything if no current fix exists.
3. Accept anything if current fix cache expired (30s, checked on both GNSS chip clock and Android clock via OR — chip clock can drift).
4. Accept if new is GPS and current is NETWORK.
5. Reject if new is NETWORK and current is GPS.
6. Reject if new fix has no accuracy field.
7. Accept if current fix has no accuracy field.
8. **Same-position shortcut**: if new is GPS and lat/lon are identical to current, accept unconditionally to keep the timestamp fresh. Prevents the "anchor fix" problem where smartphone GNSS chips lock coordinates when stationary (stationary filter) but continue varying the reported accuracy — without this, degraded accuracy causes a 30s rejection window that expires the cache and lets a wrong NETWORK fix in.
9. If current accuracy ≤ 50m: accept if new accuracy ≤ current × 3.0 (relative), OR if Doppler speed > 1.2 m/s AND new accuracy ≤ 30m (absolute+Doppler path, prevents anchor fix in motion).
10. If current accuracy > 50m: accept if new accuracy ≤ current (any improvement).

Speed and GNSS bearing are extracted from **every** incoming fix in `onLocationChanged`, even rejected ones — Doppler is computed separately from position by the chip and remains reliable even when positional accuracy degrades.

### CSV + Drive sync pipeline

1. `CsvExporter` (object, single active session) uses **lazy file creation**: `startSession()` stores prefix/context but creates no file. The file is only created on the first `appendTag()` call, using that tag's `androidTs` as the filename timestamp — this guarantees the filename matches the actual date of the first real data, not the session open time.
2. `appendTag(tag)` writes and `flush()`es each tag individually to the OS buffer immediately. This means data survives a `SIGKILL`: when the OS kills the process it closes the fd, generating a `CLOSE_WRITE` inotify event that triggers the upload pipeline — no data batch is ever held only in RAM.
3. `pendingLastTag` was removed. Winnix stop-temperature is no longer applied to the last CSV row (the tractor runs continuously; keeping the last tag in memory for hours waiting for a stop-temperature was unacceptable).
4. `rotateSession()` (runs on `autoSaveExecutor`) finalizes the current file and calls `startSession()` for the next lazy session. Triggered by time interval OR tag count threshold, coordinated via `rotationPending` (`AtomicBoolean`) to prevent double-rotation when both triggers fire simultaneously.
5. `stopCapture()` and `saveAfterError()` capture `oldAutoSaveExecutor` before calling `stopAutoSaveTimer()`, then call `oldAutoSaveExecutor.awaitTermination(10s)` inside `stopExecutor` before `finalizeSession()` — this eliminates the race condition between pending `appendTag()` writes and closing the `BufferedWriter`.
6. Finalizing a session (`finalizeSession`/`cancelSession`) closes the file under `app/files/csv/` (scoped storage, no storage permission needed). `finalizeSession()` returns `null` without creating a file if no tags were ever written — eliminates empty CSVs.
7. `drive/CsvFileObserver` watches that folder and triggers `drive/DriveUploadWorker` (WorkManager) automatically when a file is finalized (or when the OS closes it after a SIGKILL).
8. `drive/DriveHelper` manages the Drive folder hierarchy (`My Drive → "UHF Logger" → [device name]`), with a process-wide lock around check-then-create/upload to avoid duplicate folders/files under concurrent workers, and existence checks so retries don't re-upload.
9. `drive/UploadQueueDatabase` (Room) persists the upload queue so pending uploads survive process death.
10. `drive/BootReceiver` + `drive/DriveMonitorService` resume monitoring after device reboot.

### Backend SpaceVis upload pipeline (`com.uhflogger.backend`) — branch `bluetooth_V1_1_under_test`

Segundo destino de upload, independente e paralelo ao Google Drive. Com o envio desligado (padrão), nenhum código deste pacote é executado e o app se comporta exatamente como antes.

**Arquivos e responsabilidades:**

- `BackendApi` — cliente HTTP puro (`HttpURLConnection`). `status=0` = sem rede, tratado como retry pelo WorkManager.
- `BackendSettings` — wrapper de `SharedPreferences`. Distingue `isConfigured()` (intenção do operador: ativado + URL + farmId) de `isEnabled()` (pronto agora: configurado + tem token). A retenção de arquivo usa `isConfigured` para não apagar leituras só porque o token expirou momentaneamente.
- `DeviceAuthManager` — autenticação por chave de ativação única (`POST /api/auth/device/activate`). `resolveFarm()` descobre a fazenda automaticamente pelo token (best-effort); se a conta estiver vinculada a múltiplas fazendas, devolve `ActivationResult.options` (lista) e a tela pede ao operador que escolha — mesma regra do app de campo, que só auto-seleciona quando `farms.length === 1`. Renova pelo **refresh token sozinho** (`grant_type=refresh_token`): o backend monta `client_id/client_secret` do próprio ambiente dele e ignora o que vier no corpo — exigir essas credenciais do aparelho era o que matava o envio 5h após a ativação em qualquer campo onde ninguém preencheu os campos opcionais. O refresh token é offline (`scope=offline_access`), expira após 30 dias sem uso; como o worker roda a cada 15 min o token praticamente nunca expira em uso normal. Token salvo com `.commit()` síncrono para sobreviver a SIGKILL.
- `DeviceIdentity` — identidade (`ANDROID_ID`) e config de captura enviadas ao abrir cada captura no servidor. `transport` e `temp_start` são intencionalmente omitidos: o worker roda depois, às vezes dias depois, e preencher com estado atual seria dado errado.
- `CsvReadingParser` — converte linha CSV → JSON. Usa o **CSV como fila** (não duplica dados em banco). `client_event_id` é `Uuid5("arquivo|número_da_linha")` — determinístico, garante idempotência no reenvio. Todas as 11 colunas são enviadas, incluindo as colunas 8–10 (`gnss_speed`, `location_captured_at` como ISO UTC, `location_provider`). O `captureClientId` da captura é derivado do nome do arquivo; `startedAtFromFileName()` extrai o timestamp do nome do arquivo (hora local do aparelho, convertida para UTC) para preencher o `started_at` do servidor.
- `Uuid5` — UUID v5 (SHA-1, RFC 4122), mesmo algoritmo do backend JS, namespace fixo imutável. Mudar o namespace faria toda leitura já enviada aparecer como nova.
- `BackendUploadQueue` (`BackendUploadEntry` + `BackendUploadDao` + `BackendUploadStore`) — estado de envio: **uma linha por arquivo**, não por leitura. Guarda `linesSent` (progresso), `captureId`, `closed` e `driveDone`. Os dois últimos coordenam a exclusão do arquivo entre Drive e backend. Persistido no mesmo banco Room do Drive (`UploadQueueDatabase`, v3).
- `BackendUploadWorker` — Worker do WorkManager. Roteiro por arquivo: (1) abre captura no servidor (idempotente por `client_id` derivado do nome do arquivo); (2) lê CSV a partir de `linesSent`; (3) sobe em lotes de 500, grava progresso a cada lote aceito; (4) fecha captura **somente** se o arquivo não for o da sessão em andamento (`CsvExporter.activeFilePath()`). Roda periodicamente a cada 15 min (para acompanhar capturas longas em vez de esperar o Parar) + imediatamente ao ser disparado.
- `FileRetention` — coordena exclusão do CSV local. Regra: arquivo apagado só quando **ambos os destinos** terminaram; quem chegar por último apaga. Se um destino não está configurado, o outro apaga sozinho. Nunca apaga o arquivo da captura em andamento (inode sem nome causaria perda silenciosa de dados).
- `BackendSettingsActivity` — tela de configuração (código programático). Mostra (somente leitura) o servidor do build (`BuildConfig.API_BASE_URL`, com label "Produção"/"Homologação"/"Servidor personalizado"). Campos editáveis: código da fazenda (oculto por padrão — aparece só se a resolução automática falhar), chave de ativação, switch de envio. A tela aceita o deep link `uhflogger://ativar?chave=XXXX-XXXX-XXXX-XXXX` (opcionalmente `&servidor=https://...` como saída de emergência): o admin manda o link por WhatsApp, o operador toca e só precisa apertar "Ativar aparelho" — a chave não é digitada em campo. Compatível com `singleTop` via `onNewIntent` para o caso da tela já estar aberta. **Não existe mais campo de URL nem seção de `client_id/secret`** (foram removidos; o refresh token é tudo que o backend precisa para renovar).

**Mudanças em arquivos existentes:**
- `DriveUploadWorker`: substituiu `file.delete()` por `FileRetention.onDriveDone(context, file)`.
- `CsvExporter`: adicionou `activeFilePath()` — retorna caminho do arquivo ativo; usado pelo worker e pelo `FileRetention`.
- `UploadQueueDatabase`: bumped para v3, adicionou `BackendUploadEntry` como entidade com migração SQL.

### Tag filter pipeline (`com.uhflogger.filter`) — branch `bluetooth_V1_2_filtro_tags`

Reduz o volume de dados salvo no CSV e enviado ao Drive/backend, aplicado **antes** de qualquer tag chegar no `CsvExporter` — Drive e o backend SpaceVis só enxergam o que já passou pelo filtro. Ligado por padrão: com `filter_enabled=false` o comportamento é idêntico a antes desta feature. Duas camadas configuráveis pelo usuário, independentes entre si, mais uma proteção interna automática:

- **Camada 1 — família de EPC** (`EpcFamilyMatcher`): lista de padrões hex do mesmo tamanho do EPC, onde `X` aceita qualquer caractere na posição e as demais posições precisam bater exatamente (ex.: `0000100000000XXX`). Lista vazia = aceita tudo. `X` não é validado como estritamente hex — decisão deliberada de manter o matcher simples, dado que EPCs reais só usam hex.
- **Camada 2 — consolidação por EPC** (`TagFilterEngine.absorb`/`sweepExpired`): mantém só a leitura de melhor RSSI de cada EPC dentro de uma janela de tempo contada a partir do `first_seen` daquele EPC (não uma janela deslizante). Um job de sweep (`runFilterSweep`, agendado no mesmo executor do auto-save) varre periodicamente as entradas abertas e libera para o CSV as que já passaram da janela.
- **Persistência SIGKILL-safe** (`FilterStateQueue`/`FilterStateStore`) — **não é uma camada com toggle próprio**: é automática sempre que a Camada 2 está ativa. Grava em lote (`persistDirtyNow`) o estado aberto da Camada 2 em Room (tabela `filter_state`, `UploadQueueDatabase` v4, WAL). Ao reiniciar (`TagFilterEngine.start()`), recarrega qualquer entrada deixada aberta por uma sessão anterior que morreu sem passar pelo Stop normal — nunca perde a consolidação em andamento. Não existe cenário em que faça sentido consolidar em memória e aceitar de propósito perder esse progresso num crash, então essa proteção nunca é exposta como opção desligável.

**Integração em `UHFReaderService`:**
- Config (`filterEnabled`/`filterL1*`/`filterL2*`) é lida de `SettingsManager` uma vez por sessão, dentro do bloco `if (!resuming)` de `startCapture()` — não é relida a cada reconexão, pelo mesmo motivo que os outros tunables de sessão (auto-save, antena) não são.
- **D1**: Stop iniciado pelo usuário (`stopCapture(userInitiated=true)`) e `saveAfterError()` chamam `tagFilterEngine.flushAllNow()` incondicionalmente — força a expiração de tudo que está aberto, para que nenhuma leitura fique presa esperando uma janela que nunca vai fechar. Stop involuntário (`userInitiated=false`, process-death) **não** força flush — o estado persistido é recuperado no próximo `start()`.
- **D2**: com a Camada 2 ativa (`filterForcesTimeOnlyRotation`), rotação de sessão por CONTAGEM de tags brutas deixa de fazer sentido (tags já chegam consolidadas) — a rotação passa a ser só por tempo, com o mesmo intervalo da janela da Camada 2 (`autoSaveIntervalMin` é sobrescrito por `getFilterL2WindowMin()` nesse caso).
- `scheduleFilterJobs()` (persistência periódica + sweep, ambas incondicionais uma vez que a Camada 2 está ativa) roda no mesmo `autoSaveExecutor` do auto-save, sem thread pool extra — reagendado a cada `startAutoSaveTimer()`, ou seja, em toda conexão bem-sucedida (fresh start E reconexão BT/USB), igual ao `rescheduleTimerJob()` existente.
- Hook aplicado tanto no caminho Jietong (`onNewData`) quanto Winnix (`winnixOnNewData`, depois de já ter carimbado a temperatura) — `tagFilterEngine.process(tags)` retorna só as tags que devem seguir direto pro pipeline normal; as absorvidas pela Camada 2 só reaparecem quando expiram via sweep.

**UI** (`SettingsActivity`, seção "FILTRO DE TAGS"): checkboxes inline, sem diálogos — marcar "Filtro ativo" revela a configuração das camadas 1/2 no lugar; marcar a Camada 1 revela direto o campo de texto de padrões de EPC (`buildInlineTextField`); marcar a Camada 2 revela os dois campos numéricos de janela/sweep (`buildInlineNumberField`), com validação cruzada (sweep precisa ser estritamente menor que a janela, checado nos dois campos, lendo o valor ao vivo do campo irmão via `SettingsManager` em vez de um valor capturado). Campos inline usam `fieldBorderDrawable()` (fundo branco + borda visível, verde quando focado) para não se confundir com o card ao redor — distinto de `borderDrawable()`, usado nos cards/linhas de checkbox. Restaurar padrões reconstrói o `filterContainer` inteiro a partir dos defaults (`root.removeView`/`buildFilterContainer()`/`root.addView`) em vez de patchar valores por ID, já que checkboxes e campos inline não têm o formato "linha com TextView de valor" das linhas de diálogo. Não existe UI para a persistência SIGKILL-safe — não há por que o usuário desligar só a proteção contra perda de dados, mantendo a consolidação ligada.

**Defaults** (`SettingsManager`): filtro geral ligado (`DEFAULT_FILTER_ENABLED=true`); camadas 1 e 2 ativas; janela = 30 min, sweep = 5 min; Camada 1 pré-configurada com 3 padrões (`"00001000000XXXXX,0000000000000000000XXXXX,00760000000XXXXX"`) — lista vazia aceita tudo.

### Settings

`SettingsManager` is a plain `SharedPreferences` wrapper (object, static-style API) — all tunables (antenna type, Winnix antenna count/power/working time/inventory mode, auto-save thresholds, location mode, tag filter — see above) live here with `DEFAULT_*` constants. It also persists capture state (`was_capturing`, `last_device_name`) used for auto-resume after process death.

## Project history — bugs already found and fixed

The app was built and hardened over a long debugging session driven by real multi-day field tests (antenna mounted on a tractor, screen off, no supervision). Almost every non-obvious piece of state in `UHFReaderService`/`MainActivity` exists because one of these bugs actually happened in the field. Don't re-introduce them by "simplifying" without reading the reasoning first.

Chronological summary of root causes found and fixed:

1. **Bearing/GNSS freezing on screen-off** — sensors were registered from `MainActivity.onPause()`/`onResume()`; screen off killed them mid-capture. Fixed by moving all sensor/GPS/WakeLock ownership into `UHFReaderService` (see "GPS/bearing fusion" above).
2. **Process killed by Android's automatic backup (`SIGKILL`, no `onDestroy()`)** — confirmed via logcat (`FullBackup_native` immediately followed by `Sending signal ... SIG: 9`). Fixed with `android:allowBackup="false"` in the manifest.
3. **App never auto-resumed after being killed** — fixed with `START_STICKY` + persisted capture state (`SettingsManager.setCaptureState`, written with synchronous `commit()`, not `apply()`, since it must be durable before a possible imminent kill) read back in `onCreate()`.
4. **`onDestroy()` clearing "was capturing" even on involuntary process death** — fixed by adding `stopCapture(userInitiated: Boolean)`; only `userInitiated=true` (user pressed Stop) clears the persisted flag. `onDestroy()` calls it with `false`.
5–7. **GNSS accuracy-filter bugs** in `isBetterLocation()`: rejecting the very first fix for being "too inaccurate" with nothing better available; getting "stuck" on a bad first-accepted fix and refusing later, better (but still >50m) fixes; and the last-known-location cache fallback preferring GPS blindly over a much fresher NETWORK fix. All three fixed by the current `isBetterLocation()` logic described above (accept anything when nothing exists yet, only protect fixes that are already good, compare `.time` between GPS/NETWORK caches instead of preferring by provider).
8. **~94s data gap at the start of "cold" indoor sessions** — fixed by warming up GPS/WakeLock in `onCreate()` (app open, not just capture start) plus an unbounded-age last-known-location fallback.
9. **Double-tap on Start opening two capture sessions** (one could kill the other's connection) — fixed by disabling the Start button immediately on click, re-enabled only via a safety timeout (`START_BUTTON_SAFETY_TIMEOUT_MS`), not via callbacks that also fire during background auto-retries.
10. **Settings menu editable during an automatic reconnect**, letting the user "save" config that silently wouldn't apply until the next real stop/start — fixed with `isSessionActive` in `MainActivity` (see above).
11. **"Zombie" Bluetooth connection**: socket reports connected, no exception ever thrown, but `read()` never returns more data (confirmed in a 12h+ field test — Stop command still worked, proving the write path was fine, only read was stuck). Fixed with the silence watchdog (`watchdogTick`), which never assumes death from silence alone — it actively probes (`start_inventory`) first, and only forces a reconnect after `WATCHDOG_MAX_FAILED_PROBES` consecutive unanswered probes.
12. **Reconnect loop dying silently on the first unexpected exception** — `startCapture()` was called inside the BT/USB reconnect loops with no `try/catch`; any exception anywhere inside it (GPS, WakeLock, file I/O...) killed the loop's `Runnable` for good, with nothing logged (the `Future` was never awaited). Suspected root cause of a real 12h+ test where the app silently stopped reconnecting. Fixed by wrapping the loop body in `try/catch` that logs and keeps looping.
13. **Location permission never retried** — a single `AtomicBoolean` guarded both sensor and location registration; if location permission wasn't yet granted on the very first `onCreate()` call, the flag latched `true` forever and location was never registered again even after permission was granted. Fixed by splitting into `sensorsRegistered` (register-once, no permission needed) and `locationRegistered` (`@Volatile`, retried on every call to `startLocationAndSensors()` until it succeeds once).
14. **CSV temperature applied to the wrong column** — `CsvExporter` used to hold the pending last row as an already-serialized string and append `,$stopTemperature` to it, assuming Temperature was the last column. Broke as soon as the 3 new location columns were added after it. Fixed by holding a `TagRecord` (`pendingLastTag`) instead of a string and using `.copy(temperature = ...)` before serializing.
15. **(ESP32 firmware, not this repo)** — byte-at-a-time BT relay was a bandwidth bottleneck causing dropped data under fast tag bursts; missing header validation on the phone→module direction; buffer overflow risk from an unvalidated packet length. All fixed in the firmware (batched writes, header validation with resync, bounds-checked length) — relevant here only because it explains why the Android-side decoders/reconnect logic must tolerate corrupt/truncated frames gracefully.
16. **Empty CSV files (header only, no data)** — `startSession()` eagerly created the file and flushed the header immediately; if the process was restarted by `START_STICKY` but no tags arrived before the next stop (tractor returned to base, killed by OOM, etc.), a header-only file was left on disk and uploaded to Drive. Fixed by making file creation lazy: `startSession()` now only stores prefix/context; the file is created inside `appendTag()` on the first real tag.
17. **CSV filename date mismatch** — the file was named with the session-open timestamp, but the tractor could park for hours (or overnight) with no tags in range before the first read of the day, resulting in a file named for day N containing only data from day N+1. Fixed as part of the lazy-creation refactor: the filename timestamp comes from the `androidTs` of the first tag written, not from `startSession()`.
18. **CRÍTICO (corrigido): BT pode "zumbiar" para sempre com um frame corrompido** — `0xA5 0x5A` (cabeçalho Winnix) pode aparecer por acaso dentro dos bytes de payload de um EPC/RSSI (valores livres, não reservados), produzindo um `length` curto demais para conter cmd+check+CRLF. Isso lançava `IndexOutOfBoundsException` ao acessar `packet[4]` em `WinnixProtocolDecoder.feed()`, exceção que não era capturada em nenhum ponto do caminho de chamada. Como `onNewData()` roda dentro de um `Runnable` submetido a um `Executor` (`BluetoothInputOutputManager.run()`) sem que ninguém chame `.get()` no `Future`, a exceção matava a thread de leitura em silêncio — sem crash, sem log. `isRunning` ficava travado em `true` para sempre, e nem o watchdog nem `forceReconnectDueToSilence()` detectavam a queda, pois esses mecanismos dependem de uma leitura bloqueada que lance `IOException` ao forçar o fechamento da porta — não havia mais leitor bloqueado para receber essa exceção. Resultado: BT "conectado" para sempre, sem nunca mais processar dados, sem qualquer recuperação automática. Corrigido em três camadas defensivas: (1) checagem de `packet.size < 5` em `WinnixProtocolDecoder.feed()` antes do acesso a `packet[4]`, descartando o frame falso-positivo; (2) `winnixOnNewData()`/`onNewData` (Jietong) em `UHFReaderService` envolvidos em `try/catch`, mesmo padrão já usado nos loops de reconexão (item 12); (3) `BluetoothInputOutputManager.run()` também envolve a chamada a `listener.onNewData()` em `try/catch`, como segunda camada de defesa caso algo escape do listener.

**Confirmed NOT bugs** (investigated and ruled out — don't "fix" these again):
- EPCs that are all-digit (no hex letters) losing leading zeros or turning into scientific notation when opened in Excel/LibreOffice/Drive preview — spreadsheet auto-number-detection artifact, not a data corruption issue. The CSV on disk is correct; open via `File > Open` and import the EPC column as Text.
- Multi-tag inventory mode running hotter than Adaptive mode — documented Winnix behavior (100% duty cycle by design), not an app bug.

### CSV format details

Header: `EPC,RSSI,Antenna,Timestamp,Latitude,Longitude,Bearing,Temperature,GNSS Speed,Location Timestamp,Location Provider`. The first 8 columns are the original format, unchanged in order — `GNSS Speed`/`Location Timestamp`/`Location Provider` were appended, not inserted, to stay backward-compatible with anything reading by column position. `GNSS Speed` is in m/s and left empty (not `0`) when `hasSpeed()==false`. `Location Provider` stores the translated label `"GNSS"`/`"NETWORK"`, not Android's internal `gps`/`network` provider name.

Filenames: `<identifier>_<yyyyMMdd>_<HHmmss>.csv`, where `<identifier>` is the paired device's MAC (colons stripped, BT) or `DriveHelper.getDeviceName()` (USB) — see `buildFileIdentifier()` in `UHFReaderService`. The timestamp in the filename reflects the `androidTs` of the **first tag actually written**, not the session open time (lazy creation — see CSV pipeline above). The Drive folder structure doesn't depend on the filename; it's keyed by a separately cached folder ID.

### Deferred / open items (discussed, not implemented — don't assume these exist)

- Winnix "Ultra Low Power" inventory mode (protocol supports it, `SettingsManager` doesn't expose it — only Multi-tag/Fast/Adaptive exist today).
- Continuous RSSI reading for an established Bluetooth Classic (RFCOMM) connection — no clean official Android API for this (BLE-only); explicitly ruled out.
- Exposing the ESP32/module MAC address anywhere beyond the CSV filename (e.g. in the UI or as its own CSV column).
- GNSS accuracy as an extra CSV column.
- **Auto-resume não sobrevive a um reboot completo do aparelho** — descoberto testando o CSV lazy com o filtro ativo (2026-08-18): `BootReceiver` só inicia `DriveMonitorService` ([BootReceiver.kt](app/src/main/java/com/uhflogger/drive/BootReceiver.kt)); nada reinicia `UHFReaderService`. O `wasCapturing` check em `UHFReaderService.onCreate()` (item 3 do histórico de bugs) só é executado quando o serviço é recriado — o que só acontece hoje via `MainActivity` (abrir o app) ou `START_STICKY` (processo morto, mas o SO continua de pé). Num reboot de verdade (queda de energia, crash do SO), a captura fica parada até alguém abrir o app manualmente — só o CSV já salvo antes do reboot é recuperado (via `scanAndEnqueueExistingFiles()` do `DriveMonitorService`, que roda independente disso). Isso é uma lacuna real dado o uso do app (trator desatendido por dias), não um comportamento intencional.
  - Solução proposta (não implementada): em `BootReceiver`, checar `SettingsManager.wasCapturing(context)` **antes** de decidir iniciar o `UHFReaderService` — só chamar `startForegroundService(Intent(context, UHFReaderService::class.java))` se `true` (mesma condição que `onCreate()` já usa). Não checar dentro do `onCreate()` sem gate no receiver, senão o serviço liga GPS/WakeLock/notificação à toa quando não havia captura ativa. Reusa 100% da lógica de retomada já existente e testada (mesmo caminho do OOM-kill) — sem estado novo. `BOOT_COMPLETED` é isento das restrições de background-start do Android 12+ pra subir foreground service, mesma exceção que `DriveMonitorService` já usa.
- **Dois pontos onde a cor do botão Start fica dessincronizada** (clicável mas com tint "desabilitado"): [MainActivity.kt:144-146](app/src/main/java/com/uhflogger/MainActivity.kt#L144-L146) (permissão USB negada) e [MainActivity.kt:391](app/src/main/java/com/uhflogger/MainActivity.kt#L391) (dispositivo USB não encontrado) fazem `btnStart.isEnabled = true` sem chamar `updateButtonColors()` depois, diferente dos outros pontos corretos (linha 378, 99). Fix trivial: adicionar a chamada nesses dois lugares.
- **GPS waypoint logging during capture** — while a session is active and the antenna is connected, a periodic timer inserts "waypoint" rows into the CSV to record the route even between RFID reads. Each waypoint row has EPC `"marcador"`, empty RSSI and Antenna fields, and all location fields filled (Latitude, Longitude, Bearing, GNSS Speed, Location Timestamp, Provider). The timer only logs when `hasSpeed()` is true and speed exceeds a threshold; once movement is detected, logging continues even if the vehicle stops briefly, suspending only after a configurable stop-timeout (hysteresis, same pattern as `BEARING_SPEED_HIGH_MS`/`BEARING_SPEED_LOW_MS`). Open decisions before implementing: (1) timer interval — fixed or UI-configurable, default value; (2) stop-timeout duration — fixed or UI-configurable; (3) speed threshold — reuse `BEARING_SPEED_LOW_MS` (0.5 m/s) or expose separately; (4) whether waypoints count toward session rotation thresholds; (5) whether waypoints should trigger lazy CSV file creation (i.e. before the first real RFID tag); (6) exact EPC marker string (`"marcador"`, `"WAYPOINT"`, etc.).
- **Suporte a múltiplos nomes de dispositivo BT** — hoje o app aceita apenas o nome exato `"Winnix_BT"` (hardcoded como `BT_DEVICE_NAME` em `UHFReaderService`), usado tanto como filtro de dispositivos pareados quanto como identificador de sessão. A mudança planejada é suportar também o padrão `"spacevis_RFID_XXXX"` (onde `XXXX` são os últimos 4 chars hex do MAC do módulo), mantendo o legado. Approach decidido: substituir o match exato por uma função `isBtDeviceAllowed(name)` que aceita `name == "Winnix_BT"` OU `name.startsWith("spacevis_RFID_") && name.length == comprimento_fixo`; não usar wildcards genéricos. O identificador de sessão já é dinâmico (`activeDeviceName`, `SettingsManager.getLastDeviceName()`) — a reconexão já sabe buscar o nome real, não a constante. O `ACTION_ACL_CONNECTED` receiver em `MainActivity` também precisa usar a mesma função em vez de `== BT_DEVICE_NAME`. Se dois dispositivos pareados passarem no filtro ao mesmo tempo, ambos aparecem na lista e o usuário escolhe; a reconexão automática busca pelo nome exato da sessão ativa.

### Versioning

Version is set in `app/build.gradle` (`versionName` + `versionCode`). **Increment on every change**, no exceptions:

- `versionCode` — always increment by 1 (integer, used by Android to detect upgrades).
- `versionName` — semantic: `MAJOR.MINOR.PATCH`.
  - `PATCH` (`x.x.+1`) — bug fixes, config/default changes, UI tweaks.
  - `MINOR` (`x.+1.0`) — new user-visible features or significant behaviour changes.
  - `MAJOR` (`+1.0.0`) — breaking changes or major redesigns.

Current version: **1.2.6** (versionCode 11). O flavor `hml` acrescenta `-hml` ao `versionName` (ex.: `1.2.6-hml`); o `versionCode` é o mesmo para os dois. Always commit the version bump together with the change that triggered it, not as a separate commit afterwards.

Branch **`fix/csv-lazy-write`** — contém as implementações de CSV lazy creation, rotação de sessão, defaults de configuração (Winnix, 2 antenas, Adaptive, 5k tags), fix de labels no SettingsActivity, e melhorias de GNSS.

Branch **`bluetooth_V1_1_under_test`** — contém o módulo de envio ao backend SpaceVis (`com.uhflogger.backend`). Destaques acumulados na branch:
- Autenticação por chave de ativação única; upload incremental em lotes de 500; coordenação de retenção de arquivo entre Drive e backend.
- Envio de dados GPS por leitura (colunas 8–10 do CSV: `gnss_speed`, `location_captured_at`, `location_provider`).
- Product flavors `hml`/`prd` com `API_BASE_URL` compilado no artefato (sem campo de servidor em tela).
- Ativação por deep link `uhflogger://ativar?chave=XXXX` — operador toca link enviado por WhatsApp.
- Fix crítico de renovação de token: o app agora renova só com o refresh token, sem exigir `client_id/secret` (bug que matava o envio 5h após ativação em todo aparelho de campo).

Current branch: **`bluetooth_V1_2_filtro_tags`** (a partir de `bluetooth_V1_1_under_test`) — contém o filtro de tags descrito em "Tag filter pipeline" acima.

### Signing / distribution

Distributed as a manually-signed release APK (sideloaded, not Play Store), reusing the **same keystore** across versions so installs are treated as updates (preserves Drive sign-in and saved settings) rather than forcing a fresh install. Google Sign-In requires a separate OAuth Android Client ID per SHA-1 fingerprint registered in Google Cloud Console — debug and release builds need their own Client ID entries (unlike Firebase, which accepts multiple fingerprints per client).

### Validating changes without a physical device

Historically, changes to `UHFReaderService` logic were validated two ways in the absence of a real Android device: (1) compiling the real project files against hand-written stubs of the Android SDK/AndroidX/`usb-serial-for-android` types, to catch type/signature errors; (2) isolated pure-Kotlin logic tests for the sensitive decision functions (`isBetterLocation`, bearing hysteresis, watchdog probe/reconnect behavior, the location-permission-retry bug). Treat this as a floor, not a substitute for a real Gradle build and, where possible, an actual field/device test before calling a change to this service done.

## Notes for making changes

- Comments in this codebase are written in Portuguese and are often load-bearing — they explain *why* a piece of state exists or why an obvious-looking simplification was already tried and reverted (race conditions, Doze/background restrictions, real field failures observed on multi-day captures). Read them before "cleaning up" surrounding code.
- The app targets long-running unattended background operation as a first-class case (Doze, screen off for days, BT/USB dropouts, process death/restart) — when touching `UHFReaderService`, consider what happens across a reconnect, a process kill, and a screen-off period, not just the happy path.
