# Build e ambientes

O ambiente é propriedade do **artefato**, não uma configuração de tela. Não há
campo de servidor em lugar nenhum do app: escolher a variante de build é a única
forma de decidir para onde as leituras vão. É o mesmo padrão do app de campo
(`spacevis-mobile`), onde o `eas.json` define a URL por perfil.

Isso é deliberado. Com endereço editável, o mesmo aparelho pode acabar mandando
dado de produção para homologação sem ninguém perceber.

## Os dois ambientes

|                        | Homologação                                   | Produção                                      |
| ---------------------- | --------------------------------------------- | --------------------------------------------- |
| Build Variant          | `hmlDebug` / `hmlRelease`                     | `prdDebug` / `prdRelease`                     |
| Nome no aparelho       | **UHF Logger HML**                            | **UHF Logger**                                |
| `versionName`          | sufixo `-hml`                                 | sem sufixo                                    |
| API                    | `backend-cattlevis-q3zndqt2pq-uc.a.run.app`   | `backend-cattlevis-3p6nhqmpoa-uc.a.run.app`   |
| Projeto GCP            | `stocci-farm-homolog`                         | `stocci-farm-prod`                            |
| Cloud SQL              | `postgresql-cattlevis`                        | `cattlevis-database`                          |
| Onde gerar a chave     | plataforma de homologação                     | plataforma de produção                        |

> **O `applicationId` é o mesmo nos dois flavors**, de propósito: o cliente OAuth
> do Google Drive é atrelado a package + SHA-1, e um sufixo `.hml` quebraria o
> login do Drive no build de homologação. Consequência aceita: **um app por vez
> por aparelho** — desinstale o outro antes de instalar.

## Como buildar

### Android Studio

1. `View → Tool Windows → Build Variants`
2. Selecione a variante do módulo: `prdDebug` para testar em campo,
   `prdRelease` para distribuir
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`

O APK sai em `build/outputs/apk/<flavor>/<buildType>/`. **Confira o caminho**: se
saiu em `hml/`, a variante não foi trocada.

### Linha de comando

```bash
./gradlew assemblePrdRelease    # produção, assinado (ver abaixo)
./gradlew assemblePrdDebug      # produção, chave de debug
./gradlew assembleHmlDebug      # homologação
```

## Assinatura do release

Sem credencial configurada, `assemblePrdRelease` produz
`app-prd-release-unsigned.apk` — que o Android **recusa instalar**. O build não
falha: sai um APK aparentemente pronto que só revela o problema no aparelho.
Por isso o Gradle emite um aviso quando as credenciais faltam.

Para assinar:

```bash
cp keystore.properties.example keystore.properties
# preencha storeFile, storePassword, keyAlias, keyPassword
```

`keystore.properties` e `*.jks` são ignorados pelo git. Em CI, use as variáveis
`UHFLOGGER_STORE_FILE`, `UHFLOGGER_STORE_PASSWORD`, `UHFLOGGER_KEY_ALIAS` e
`UHFLOGGER_KEY_PASSWORD`. A ordem de procura é: `keystore.properties` → `-P` na
linha de comando → variável de ambiente.

> **Trocar o keystore quebra o login do Google Drive** até que o novo SHA-1 seja
> cadastrado no cliente OAuth. Guarde-o com backup: sem ele não há como atualizar
> o app já instalado em campo.

## Ativando o aparelho

A chave de ativação pertence a **um ambiente**. Chave gerada em produção não
funciona num APK de homologação — o servidor responde `400` e a tela mostra
"chave inválida ou expirada". Esse foi o erro mais comum nos testes de campo.

1. Gere a chave na plataforma do ambiente **correspondente ao APK**
   (Usuários → Dispositivos → Gerar chave)
2. Mande o link para o operador:
   `uhflogger://ativar?chave=XXXX-XXXX-XXXX-XXXX`
3. Antes de tocar em **Ativar aparelho**, confira o campo "Servidor" na tela —
   ele mostra o ambiente e a URL. É a checagem que evita queimar a chave à toa
   (ela é de uso único).

A fazenda **não** é digitada: o servidor a resolve pela própria chave. Quando a
conta-device tem acesso a mais de uma, o app mostra um seletor com os nomes.

### Escape hatch

O link aceita `&servidor=https://...`, que grava outra URL no aparelho sem gerar
build. Serve para destravar um teste; a tela passa a mostrar "Servidor
personalizado" em vez de "Produção". Não use como configuração permanente — é
justamente o cenário que os flavors existem para impedir.
