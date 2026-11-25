# Leitor RoyalRay (SPACEVIS_PROTOCOL)

Terceiro tipo de leitor, ao lado de Jietong e Winnix. Dentro dele há um ESP32
entre o módulo RoyalRay (RRUx180MG/182MG) e o celular: o app fala
**SPACEVIS_PROTOCOL** (protocolo nosso) e o ESP32 traduz para o módulo. Ciclo de
leitura, rodízio de antenas, modo adaptativo, proteção térmica e recuperação do
módulo ficam no ESP32. O app só configura, liga, desliga e recebe as tags.

Especificação: `GUIA_INTEGRACAO_ROYALRAY_SPACEVIS.md` (byte a byte, frames de
referência). Referência do protocolo: `PROTOCOLO.md` no repositório do firmware.

## Onde está no código

| Peça | Arquivo |
|---|---|
| Frames, CRC-16/MCRF4XX, comandos e decodificação | `spacevis/SpacevisProtocol.kt` |
| Stream SPP → frames (ressincroniza, nunca lança) | `spacevis/SpacevisFrameParser.kt` |
| Um comando por vez, 2,5 s × 3 tentativas | `spacevis/SpacevisCommandChannel.kt` |
| Validar → configurar → START; STOP → temperatura | `spacevis/SpacevisSession.kt` |
| Textos ao operador | `spacevis/SpacevisMessages.kt` |
| Integração com a captura (BT, watchdog, reconexão) | `service/UHFReaderService.kt` (`startRoyalrayBt`, `royalrayStop`) |
| Configurações | `SettingsManager` / `SettingsActivity` (seção "CONFIGURAÇÃO ROYALRAY") |

O pacote `spacevis` não tem Android: roda e é testado na JVM. É genérico de
propósito — os próximos leitores também vão falar SPACEVIS_PROTOCOL. O que é
específico do RoyalRay é só `tipo_modulo = 1` e o nome na tela.

## Configurações

| Parâmetro | Padrão | Faixa |
|---|---|---|
| Portas de antena (escolha por porta) | **1 e 2** (`0x03`) | pelo menos uma |
| Potência (mesma em todas as portas) | **33 dBm** | 5 a 33 |
| Modo | **Adaptativo** | Adaptativo / Contínuo |

O ESP32 **não grava** a config de forma permanente (perde numa falta de
energia), então o app manda a sequência inteira a **cada** conexão, inclusive nas
reconexões automáticas, sem comparar com `GET_CONFIG`. Mudança feita durante a
captura vale na próxima conexão.

Enviado ao backend em `capture_config`: `antenna_type = "royalray"`, `ant_mask`,
`power_dbm`, `royalray_mode` e `ant_count` (= portas pedidas). **Hoje o backend
guarda só `antenna_type`, `ant_count` e `power_dbm`**; `ant_mask` e
`royalray_mode` são descartados pela whitelist de `toCaptureConfig` até o backend
aceitá-los.

## Fluxo da sessão

1. Conecta SPP (só Bluetooth; RoyalRay com USB é recusado com mensagem).
2. Liga a recepção (parser) **antes** do primeiro comando.
3. `GET_INFO`: válido se respondeu, status OK, `proto_versao == 1` e
   `tipo_modulo == 1`. Se não, fecha e avisa (tipo errado / firmware mais novo).
   Numa reconexão, a falha mantém a sessão pausada e o loop tenta de novo.
4. `SET_ANTENAS` → `SET_POTENCIA` → `SET_MODO` → `GET_STATUS` (temperatura
   inicial e qualidade das antenas) → `START`, **segurando a trava de comandos**:
   um Parar no meio espera; se o Parar chegou antes do START, o START não sai.
5. Eventos `0xC0` viram `TagRecord` (EPC hex maiúsculo, RSSI com sinal, antena,
   relógio/GPS do celular) e seguem filtro → CSV → envio, como Winnix/Jietong.
   Eventos fora da captura ativa (validação, configuração, depois do Parar) são
   descartados.
6. Parar: `STOP` (espera resposta) → `GET_STATUS` (temperatura final) → fecha.

A temperatura inicial vai no **primeiro** registro e a final no **último**, igual
ao Winnix.

**Desconectar não para a leitura** — só o STOP. Se o app morrer, o `onDestroy`
escreve o STOP (`55 AA 00 06 8E 95`) sem esperar. Se o STOP ficar sem resposta
(BT caído), o leitor segue lendo até alguém mandar STOP ou faltar energia
(limitação conhecida; o adaptativo tem teto térmico).

## Watchdog e reconexão

Mesmos valores do Winnix: 30 s sem nenhum byte → sonda a cada 30 s → 3 sondas sem
resposta → fecha e reconecta (loop BT a cada 5 s). A sonda do RoyalRay é um
`GET_STATUS` escrito direto (no vigia sem tag, o leitor fica legitimamente em
silêncio). **Nunca** START como sonda: religaria a leitura depois de um STOP.

Comando sem resposta na configuração (3 × 2,5 s) é tratado como queda
(`config_error` na telemetria) e reconecta. Enquanto o celular está desconectado
o leitor continua lendo e essas tags se perdem (o leitor não tem buffer).

## Mensagens

| Situação | Mensagem | Captura continua? |
|---|---|---|
| BT não abre | "<nome>: falha ao conectar" | Não (reconexão tenta de novo) |
| GET_INFO sem resposta / tipo diferente | "<nome>: leitor não respondeu como RoyalRay. Confira o tipo de leitor." | Não |
| `proto_versao` > 1 | "<nome>: firmware do leitor é mais novo que o app. Atualize o app." | Não |
| Máscara efetiva ≠ pedida | "Leitor tem N porta(s). Lendo em: Porta 1." | Sim |
| SET_ANTENAS RECUSOU | "Módulo recusou a porta X (porta sem antena?). Lendo em: …" | Sim |
| SET_ANTENAS PARAMETRO | "Nenhuma porta escolhida existe neste leitor (N porta(s))." | Sim |
| OFFLINE em algum SET/START | "Módulo RFID sem resposta. A leitura começa sozinha quando ele voltar." | Sim |
| RL < 10 numa porta usada | "Antena da porta N com sinal ruim (RL X dB). Confira antena, cabo e conector." | Sim |
| RoyalRay com USB | "RoyalRay só funciona por Bluetooth." | Não |

"Leitor tem N porta(s)" aparece uma vez por leitor e combinação pedida → efetiva
(num módulo de 1 porta com o padrão 1+2 ele apareceria em toda conexão).

## Testes

- JVM (`src/test/.../spacevis`): todos os frames de referência do guia, CRC,
  stream picado byte a byte com lixo, ressincronização após CRC quebrado,
  timeout/reenvio, ordem START → STOP, cada aviso da tabela acima — contra o
  leitor falso `FakeSpacevisReader`.
- Bancada (guia, seção 14): ainda **não** feitos com o leitor real. Itens que só a
  bancada valida: latência real, rajada ~100 eventos/s, reconexão após desligar o
  BT do celular, Winnix configurado como RoyalRay (mensagem após ~7,5 s), captura
  longa no vigia sem reconexão espúria e regressão de Jietong/Winnix.
