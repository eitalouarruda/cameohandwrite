# Cameo Handwrite

Android 10+ para colar texto e escrever com caneta na Silhouette Cameo 5, diretamente em folhas pautadas. Kotlin, Jetpack Compose, Material 3, MVVM e DataStore. Offline, sem conta, backend ou permissão de internet.

## Usar

1. Instale o APK debug da execução mais recente de [Android APK](https://github.com/eitalouarruda/cameohandwrite/actions/workflows/android.yml), artifact `cameo-handwrite-debug`.
2. Na tela **Texto**, cole ou digite o conteúdo. O app inicia em **Letra feia**, irregularidade 80% e perfil **Rápido**. Texto e perfil de folha anteriores são mantidos.
3. Confira a prévia, que usa os mesmos trajetos da escrita. Use Anterior/Próxima se houver mais de uma página.
4. Carregue a folha como no perfil, coloque a caneta no suporte 1 e retire lâminas. Toque **Escrever página**; se necessário, selecione a Cameo na lista. Conexão, identificação do modelo e consulta de estado são feitas automaticamente. Conecte apenas com a máquina ociosa: a identificação usa o handshake documentado que reinicia o protocolo.
5. Ao terminar, confira a folha, troque o papel e selecione a próxima página. O app envia somente a página selecionada; não troca folhas nem repete automaticamente um trabalho parcial.

**Pausar/Retomar** e **Cancelar envio** agem entre blocos limitados de 512 bytes, terminados com caneta levantada. Um comando já recebido pela Cameo não é abortado instantaneamente. Em falha de comunicação, confira a máquina/caneta e use outra folha antes de repetir. Deixe o app aberto; a tela é mantida acesa durante o envio.

## Interface

- **Texto:** editor, prévia proporcional com zoom/pan, navegação por páginas e escrita. Não há botões de protocolo ou baterias de teste no fluxo principal.
- **Folha:** perfis A4/A5/personalizados, primeira pauta, distância entre pautas; margens, furos e calibração X/Y de 0,1 mm ficam recolhidos.
- **Ajustes:** fonte, tamanho, irregularidade e qualidade. **Opções avançadas** reúne alinhamento, espaço entre palavras, força/velocidade física, SVG/JSON, simulador e **Diagnóstico e testes**.

O padrão físico é força 1 e velocidade 10 na escala GPGL, usados nos testes do usuário. O perfil Rápido controla simplificação/planejamento de trajetos e não aumenta a velocidade física para 20/30 nem altera o transporte BLE. A preferência de abertura Feia/Rápido é reaplicada ao iniciar o app; outras escolhas continuam disponíveis durante o uso.

## Folha, trajetos e fontes

Coordenadas em milímetros, pautas existentes, margens, faixa dos furos e calibração por perfil. Os valores iniciais são exemplos. Linhas/furos são referências visuais e não são desenhados. O layout reserva espaço para descendentes; pautas físicas podem exceder as linhas utilizáveis. A prévia, SVG e plotagem usam a geometria planejada/calibrada, com resolução nativa de 0,05 mm no GPGL.

Fontes single-line Hershey `futural` e `scripts`, sem TTF convertido em contorno nem preenchimento. A letra feia combina formas existentes das duas famílias, inclinação/largura/altura e pequenas deformações determinísticas dos vértices, sem criar contornos duplos ou subdividir os traços. Mesmos texto, semente e parâmetros produzem os mesmos caminhos. Acentos portugueses/espanhóis, til, trema, cedilha e pontuação invertida são compostos geometricamente; caracteres ausentes bloqueiam o trabalho com aviso.

Licenças e atribuições em [licenses](licenses/), incluídas no APK. A nota Hershey preserva os créditos e a restrição ao formato NTIS; os TSV não usam esse formato.

## Escrita real e limites

O usuário confirmou conexão BLE, identificação `CAMEO 5 V1.04`, movimento M, escrita D, levantamento e cinco padrões com caneta, usando força 1 e velocidade 10. A comparação 10/20/30 não trouxe ganho: a maior parte do tempo medido estava nos ACKs BLE. A otimização de MTU foi interrompida a pedido do usuário; a versão 0.4.0 preserva o transporte validado de 20 bytes confirmados e blocos de 512 bytes.

**Páginas completas do editor estão habilitadas por software, mas sua impressão física ainda precisa ser confirmada pelo usuário.** Escala e alinhamento anteriores foram marcados como OK sem medidas de régua; não constituem calibração quantitativa. Confira sua folha/base antes de textos longos. Limites configuráveis de papel de 305 × 600 mm não certificam a área mecânica da sua base.

Validação antes de enviar: modelo Cameo 5 original, folha/margens/coordenadas finitas, página não vazia, até 200 mil pontos por página, estado pronto e comandos GPGL documentados. Papel descarregado antes de começar recusa o trabalho sem desenhar; a conexão é mantida para carregar a folha e tentar novamente. Não há recuperação automática de movimento incerto. USB OTG continua como diagnóstico, sem escrita bulk.

[Pesquisa, fontes fixadas e evidências físicas](docs/PROTOCOL.md). Baterias, comparação de velocidades, teste sem ferramenta, logs e relatório único permanecem em Ajustes → Opções avançadas → Diagnóstico e testes.

## Arquitetura e build

`MainActivity` → `HandwriteViewModel` → `StrokeFontEngine` → `PageLayoutEngine` → `PlotPlanner` → `CameoPageJob` → `CameoJobRunner` → BLE. O runner é compartilhado com a bateria anterior, sem mudar o protocolo. Perfis/texto/configurações ficam no DataStore; o último relatório técnico fica em arquivo atômico local.

JDK 17 e Android SDK 35:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Wrapper Gradle 8.11.1 versionado com SHA-256. Workflow em pushes na main e manualmente; publica APK e relatórios. A chave debug de teste é reutilizada pelo cache do Actions para atualizar sobre 0.3.0+ sem desinstalar enquanto o cache estiver preservado. Nenhum segredo/chave é incluído no repositório.

Testes cobrem glifos/acentos, irregularidade determinística, layout/paginação, margens/calibração, SVG, simplificação, equivalência da tinta na grade nativa, blocos/ACKs sem repetição, escolha de página e envio do texto real do editor. Testes em emulador cobrem a interface simplificada, persistência e relatórios. Esses testes não substituem a observação da página inteira na Cameo física.
