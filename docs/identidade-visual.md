# Identidade visual (SpaceVis)

O app usa as mesmas cores de app.spacevis.com.br. **Toda cor de tela vem de
`src/main/res/values/colors.xml`.** Não existe mais hex solto em layout nem em
Kotlin.

| Onde | Como |
|---|---|
| Layout XML | `@color/sv_*`. Os botões usam os color state lists de `res/color/btn_*` (habilitado/apagado) |
| Telas montadas em Kotlin | `Brand.color(ctx, R.color.sv_*)`, `Brand.styleButton(...)`, `Brand.card(...)`, `Brand.field(...)` (`ui/Brand.kt`) |
| Barra do topo | `Brand.withTopBar(activity, conteúdo)`: verde da sidebar + `drawable-nodpi/spacevis_logo.png` (logo oficial, 160×21 dp, sem recolorir) |
| Diálogos | `Brand.DIALOG_THEME` (também é o `alertDialogTheme` padrão do tema) |
| Fonte | Inter 400/600/700 embutida em `res/font/` (OFL, `docs/licenses/Inter-OFL.txt`), aplicada pelo tema em todo texto |
| Ícone | Símbolo SpaceVis (o mesmo do spacevis-mobile) sobre `sv_sidebar` |

Medidas: cards com raio de 16, botões e campos com raio de 10.

## Cores que não são tokens da plataforma

- `sv_field_border` (#A9BCB0): a borda de campo. O `input` da plataforma some
  num campo branco sobre fundo claro, e isso já gerou reclamação de "caixa sem
  borda".
- `sv_warning_text` (#A8620F): aviso usado como cor de texto. O `warning` puro
  tem contraste 2,2:1 sobre branco e fica ilegível ao sol.
- `sv_warning_soft` / `sv_error_soft`: fundos dos cartões de aviso e de erro.

`BrandColorsTest` trava os tokens nos valores da plataforma. Se a plataforma
mudar uma cor, atualize o `colors.xml` e o teste juntos.
