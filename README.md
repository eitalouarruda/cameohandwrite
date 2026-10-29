# Cameo Handwrite

Aplicativo Android 10+ em Kotlin/Compose para preparar escrita de traço único em folhas pautadas. Interface em português, offline, sem conta, backend ou permissão de internet.

**Status:** editor, layout, prévia, SVG, calibração, simulador e diagnóstico BLE/USB implementados. **A escrita física pela Cameo 5 está bloqueada nesta versão.** Não foi disponibilizada uma Cameo para validar origem, caneta, fluxo e cancelamento. Instalar o APK não implica suporte físico validado.

## Instalar

1. Abra [Actions → Android APK](https://github.com/eitalouarruda/cameohandwrite/actions/workflows/android.yml).
2. Escolha a execução mais recente com sucesso e baixe o artifact `cameo-handwrite-debug`.
3. Extraia o ZIP e instale `app-debug.apk` no celular, permitindo instalação pelo aplicativo usado para abri-lo.

Android 10 (API 29) ou superior. Não precisa conectar a Cameo para editar, visualizar ou simular. APK debug, sem assinatura de release. Para atualizar builds debug de runners diferentes, pode ser necessário desinstalar a versão anterior; exporte suas configurações antes.

## Uso

- **Editor:** cole até 200 mil caracteres, escolha estilo, altura em mm, espaço entre palavras, alinhamento e irregularidade. Caracteres sem glifo bloqueiam o layout com aviso; não são descartados silenciosamente.
- **Folha:** configure dimensões, margens, faixa dos furos, primeira pauta e distância entre pautas; salve perfis. Valores iniciais são exemplos, não medidas da sua folha. A contagem de pautas físicas pode exceder a de linhas usadas: o layout reserva espaço para descendentes na última pauta. Use os perfis A4/A5 ou um tamanho personalizado.
- **Prévia:** navegue pelas páginas, arraste/amplie com dois dedos e veja a ordem colorida dos traços. Exporte cada página em SVG vetorial em milímetros. Linhas e furos aparecem apenas como referências na prévia.
- **Escrita:** escolha Rápido/Normal/Caprichado, simplificação e velocidades para estimativa. Simule a página, pause, retome e cancele. O progresso representa traços simulados; não o estado da Cameo. O simulador é acelerado, não reproduz a duração estimada em tempo real.
- **Calibração:** posicione a folha, exporte as cruzes de teste, execute o SVG por um fluxo físico já validado, meça erro X/Y e aplique a correção oposta. Salve o perfil. Passos de 0,1 mm; sem compensação automática de rotação ou distorção da folha.
- **Ajustes:** exporte/importe configurações JSON com texto e perfis. Mesmo texto, semente e parâmetros geram a mesma geometria. As margens são nominais; a calibração translada a geometria uma vez, com validação final dentro da folha. Os arquivos exportados incluem o texto; compartilhe-os conscientemente.

A altura nominal corresponde à altura das maiúsculas; acentos e descendentes ocupam espaço adicional. Letras, pautas e calibração incompatíveis produzem erro, nunca recorte silencioso da escrita. O limite configurável de papel é 305 × 600 mm; isso não certifica a área mecânica da sua base/Cameo.

## Fontes e trajetos

Dados Hershey `scripts` e `futural`, convertidos em polilinhas M/L. Cinco estilos na interface: simples (script com variação leve), cursiva (script), forma (futural), irregular e feia (futural com transformações). Não são cinco famílias independentes. Sem TTF convertido em contorno, preenchimentos ou rasterização. As letras cursivas mantêm os levantamentos da fonte; não há promessa de palavras inteiras sem levantar a caneta.

Português/espanhol: composição NFD de agudo, grave, circunflexo, til, trema e cedilha sobre ASCII, incluindo maiúsculas. Pontuação invertida e aspas tipográficas têm fallback geométrico explícito. Outros alfabetos, emoji e marcas combinantes sem glifo não são suportados. Não há variantes alternativas de desenho por caractere.

Variações determinísticas de inclinação, altura, largura e posição vertical, indexadas pela semente e paginação. O planejador simplifica por Douglas–Peucker, remove segmentos duplicados exatos e pode inverter o sentido de um traço para aproximar seu início, preservando a sequência das letras/palavras. A geometria planejada alimenta prévia, SVG e simulação. Não são usados atalhos que conectem letras atravessando espaços com a caneta abaixada.

Licenças e créditos em [licenses](licenses/), incluídos também no APK. A nota original Hershey preserva atribuições e a restrição ao formato NTIS; os arquivos TSV não usam esse formato.

## Cameo 5: o que está implementado

- Busca BLE real com permissões Android 10/11 e 12+, seleção, conexão GATT, descoberta de serviços/características, desconexão e logs exportáveis.
- Comparação com UUIDs publicados no upstream, sem inferir protocolo a partir de qualquer característica gravável.
- Consulta opcional de identificação: assina notificações de controle/resposta, usa handshake documentado `ESC EOT` e consulta `FG`. **Use somente com a máquina ociosa:** o handshake reinicia o estado do protocolo. Sem comandos de movimento.
- Inspeção USB OTG real de dispositivos, interfaces e endpoints; identificação do VID/PID documentado. Sem permissão ou transmissão bulk nesta versão.
- Driver de serialização GPGL: Y,X em 20 unidades/mm, M/D, ETX, limites físicos e blocos de até 1024 bytes preservando comandos. Separado do layout e dos diagnósticos.
- Contrato `PlotterTransport` e transporte gravador para verificação offline.

Não implementados/liberados: transmissão física de trabalhos BLE/USB, configuração de ferramenta/pressão, controle físico de velocidades, pausa/cancelamento da Cameo, pareamento forçado ou reconexão/retomada automática de trabalho. Uma desconexão não prova que o equipamento parou; jamais reenviar automaticamente um trabalho parcial.

[Pesquisa e fontes fixadas do protocolo](docs/PROTOCOL.md). O upstream relata teste com Cameo **5 Alpha**; não foi tratado como teste da Cameo 5 original nem deste APK. A ausência de acesso ao equipamento não impede testar todo o fluxo offline.

## Arquitetura e build

`MainActivity`/Compose → `HandwriteViewModel` → `StrokeFontEngine` → `PageLayoutEngine` → `PlotPlanner`; DataStore para persistência. Diagnósticos BLE/USB e driver GPGL ficam separados do motor geométrico. Trabalhos de layout executam em dispatcher de CPU com debounce; persistência é automática.

JDK 17 e Android SDK 35:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Workflow em `.github/workflows/android.yml` roda em pushes na `main` e manualmente; publica APK e relatórios. Gradle Wrapper 8.11.1 versionado com SHA-256 da distribuição. Nenhuma chave ou segredo no repositório.

Testes cobrem glifos reais, acentos, fallback ausente, escala em mm, margens, quebras/páginas, alinhamentos, calibração, irregularidade determinística, simplificação, duplicatas, limites e serialização/fracionamento GPGL. Isso não substitui testes no Android nem na Cameo.

## Validação física pendente

1. Instalar no celular e testar busca/conexão; exportar diagnóstico com o equipamento ocioso.
2. Comparar modelo, firmware e UUIDs reais; distinguir Cameo 5 original, Plus e Alpha.
3. Validar levantamento da caneta e origem com base carregada; começar sem caneta e movimentos mínimos em papel descartável.
4. Medir deslocamento e inclinação em vários pontos; ajustar folha e perfil.
5. Validar fluxo/status, velocidades reais, desconexão, mídia descarregada, pausa e parada antes de habilitar transmissão de texto longo.
