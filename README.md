# UHF Logger

App Android que lê tags RFID UHF (leitor Chainway por USB ou Bluetooth), grava as
leituras com GPS em CSV e as envia para o **backend SpaceVis** e para o **Google
Drive** — dois destinos independentes.

O CSV local só é apagado depois que o servidor confirma o recebimento.

## Ambientes

O ambiente é propriedade do **build**. Não existe campo de servidor em tela
nenhuma: trocar a Build Variant é a única forma de mudar para onde os dados vão.

|                    | Homologação                                 | Produção                                    |
| ------------------ | ------------------------------------------- | ------------------------------------------- |
| Build Variant      | `hmlDebug` / `hmlRelease`                   | `prdDebug` / `prdRelease`                   |
| Nome no aparelho   | **UHF Logger HML**                          | **UHF Logger**                              |
| API                | `backend-cattlevis-q3zndqt2pq-uc.a.run.app` | `backend-cattlevis-3p6nhqmpoa-uc.a.run.app` |
| Projeto GCP        | `stocci-farm-homolog`                       | `stocci-farm-prod`                          |

> O `applicationId` é o mesmo nos dois flavors (o OAuth do Drive é atrelado a
> package + SHA-1). **Um app por vez por aparelho** — desinstale o outro antes.

## Buildar para testar

Use as variantes **`Debug`**: não precisam de keystore e instalam por cima da
versão anterior, preservando a fila local de envio.

1. `git checkout main && git pull`
2. Abra no Android Studio e aguarde o Gradle Sync
3. `View → Tool Windows → Build Variants` → escolha `prdDebug` ou `hmlDebug`
4. Clique em ▶ Run (instala direto no aparelho)

O APK fica em `build/outputs/apk/<flavor>/debug/`. **Confira o caminho**: se saiu
em `hml/` quando você queria produção, a variante não foi trocada.

## Buildar para distribuir

As variantes **`Release`** precisam de keystore — sem ele o APK sai
`-unsigned` e o Android recusa instalar (o build **não** falha; o erro só aparece
no aparelho).

1. Peça o keystore à equipe (`uhflogger-release.jks`). Se não existir:
   ```bash
   keytool -genkeypair -v -keystore uhflogger-release.jks \
     -alias uhflogger -keyalg RSA -keysize 2048 -validity 10000
   ```
2. ```bash
   cp keystore.properties.example keystore.properties
   ```
   Preencha `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
   O arquivo é ignorado pelo git — **nunca commite**.
3. **Se o keystore for novo**, cadastre o SHA-1 no cliente OAuth Android do
   projeto `uhf-logger` no Google Cloud Console:
   ```bash
   keytool -list -v -keystore uhflogger-release.jks -alias uhflogger | grep SHA1
   ```
   Sem isso o **login do Google Drive para de funcionar**, com um erro que não
   diz que a causa é o certificado.
4. Build Variant → `prdRelease` → `Build → Build Bundle(s)/APK(s) → Build APK(s)`
5. Confirme a assinatura:
   ```bash
   apksigner verify --print-certs build/outputs/apk/prd/release/app-prd-release.apk
   ```

⚠️ A assinatura muda de debug para release, então **não dá para atualizar por
cima** — é preciso desinstalar. Antes disso, abra a tela do servidor e confirme
que mostra **"0 arquivo(s) aguardando envio"**: desinstalar apaga os CSVs que
ainda não subiram.

⚠️ Guarde o keystore com backup. Sem ele **não há como atualizar** o app já
instalado em campo.

## Testar em campo

1. Instale o APK do ambiente desejado
2. Abra o app → **Servidor SpaceVis**
3. Confira o campo "Servidor" — ele mostra o ambiente e a URL. Faça isso **antes**
   de digitar a chave: ela é de uso único
4. Gere a chave na plataforma **do mesmo ambiente**
   (Usuários → Dispositivos → Gerar chave) e mande o link ao operador:
   ```
   uhflogger://ativar?chave=XXXX-XXXX-XXXX-XXXX
   ```
5. Toque em **Ativar aparelho**
6. Faça uma captura e confirme no banco do ambiente:
   ```sql
   select id, farm_fk, source_file, processing_status,
          (select count(*) from antenna_reading_raw r where r.capture_fk = c.id) as leituras
   from antenna_capture c order by id desc limit 5;
   ```

A fazenda **não** é digitada: o servidor a resolve pela chave. Se a conta-device
tiver acesso a mais de uma, o app mostra um seletor com os nomes.

### Erro mais comum

> **"Chave de ativação inválida ou expirada"**

A chave pertence a **um ambiente**. Chave de produção num APK de homologação
falha assim — a mensagem não diz nada sobre a causa real. Confira o campo
"Servidor" e gere a chave no ambiente correspondente.

### Diagnóstico

A tela do servidor mostra quantos arquivos aguardam envio e **"Última falha: ..."**
quando algo trava (sem conexão, sessão recusada, lote grande demais). É o primeiro
lugar a olhar antes de ir atrás de log de servidor.

## Documentação

- [`docs/build-e-ambientes.md`](docs/build-e-ambientes.md) — detalhes de build,
  assinatura e ativação
