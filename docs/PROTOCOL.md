# Cameo 5: evidências e limites do protocolo

Pesquisa realizada em 29/10/2026. Este documento distingue evidência em projetos externos de validação deste aplicativo. **Nenhuma Cameo física foi usada nesta implementação.** Cameo 5 original e Cameo 5 Alpha são modelos diferentes; validação em um não comprova o outro.

## Fontes primárias fixadas

- [inkscape-silhouette, revisão f376a083](https://github.com/fablabnbg/inkscape-silhouette/tree/f376a0836b53826e57f50d7ec44658663e629f05).
- [Knutcut, revisão 08b97230](https://github.com/knutwurst/knutcut/tree/08b97230e32ce0fd6e93c423da5bca8cddcb6c02).

O [README do inkscape-silhouette](https://github.com/fablabnbg/inkscape-silhouette/blob/f376a0836b53826e57f50d7ec44658663e629f05/README.md#bluetooth) relata teste físico BLE com Inkscape 1.4.4, macOS, Cameo **5 Alpha** e adaptador de caneta. O [README do Knutcut](https://github.com/knutwurst/knutcut/blob/08b97230e32ce0fd6e93c423da5bca8cddcb6c02/README.md) lista Cameo 3/4/5 via BLE GPGL. Isso é evidência externa, sem comprovação de execução deste aplicativo na Cameo 5 original.

## GATT BLE documentado

Fonte: [BLETransport.py](https://github.com/fablabnbg/inkscape-silhouette/blob/f376a0836b53826e57f50d7ec44658663e629f05/silhouette/BLETransport.py).

| Papel | UUID publicado |
| --- | --- |
| Serviço proprietário | `e2088282-4fde-42f9-bb22-6ec3c7ed8f91` |
| Escrita de comandos | `6d92661d-f429-4d67-929b-28e7a9780912` |
| Respostas | `8dcf199a-30e7-4bd4-beb6-beb57dca866c` |
| Controle/eventos | `61490654-b5b4-458c-a867-9e15bc1471e0` |

O upstream valida o serviço, assina notificações de controle e respostas, escreve `ESC EOT` (`1b 04`) nas características de controle, resposta e escrita, com confirmação ATT, e aguarda um segundo. A inicialização altera o estado da máquina: **conectar para diagnóstico não deve executar automaticamente esse handshake**.

O transporte upstream divide a transmissão em blocos de 20 bytes com confirmação ATT e trata backpressure. Respostas GPGL são delimitadas por ETX. Eventos de movimento da característica de controle não devem ser misturados às respostas da característica READ: ambos podem conter `0`/`1` com ETX, mas têm significados e contextos distintos. Descoberta de nome e existência de uma característica gravável não bastam para autorizar envio.

Knutcut usa outra estratégia: [BluetoothLePlotter.kt](https://github.com/knutwurst/knutcut/blob/08b97230e32ce0fd6e93c423da5bca8cddcb6c02/android/app/src/main/kotlin/de/knutwurst/knutcut/transport/BluetoothLePlotter.kt) escolhe o primeiro par write/notify de um serviço. Essa heurística não identifica com segurança um protocolo proprietário e não é prova de UUID/modelo.

## GPGL e USB

Fontes: [Graphtec.py](https://github.com/fablabnbg/inkscape-silhouette/blob/f376a0836b53826e57f50d7ec44658663e629f05/silhouette/Graphtec.py), [Transport.py](https://github.com/fablabnbg/inkscape-silhouette/blob/f376a0836b53826e57f50d7ec44658663e629f05/silhouette/Transport.py).

- Cameo 5 original: USB VID `0x0b4d`, PID `0x1140`; Plus: PID `0x1141`. Alpha usa outro VID (`0x3844`), portanto não deve ser confundida.
- O transporte USB upstream usa bulk OUT `0x01`, IN `0x82`. No Android, enumerar e validar descritores/interfaces reais, obter permissão USB e usar os endpoints efetivamente expostos antes de qualquer envio.
- Coordenadas GPGL usam 20 unidades por milímetro, na ordem **Y,X**. `M` movimenta com caneta levantada; `D` desenha. Comandos terminam em ETX (`03`).
- `FG` + ETX consulta versão/modelo. `ESC ENQ` (`1b 05`) consulta estado. A resposta `0` indica pronto, `1` movimento e `2` mídia descarregada na implementação upstream.
- `ESC EOT` inicializa/reset; não deve ser apresentado como cancelamento comprovadamente seguro.
- `safe_write` do upstream limita blocos a 1024 bytes, preserva comandos completos e consulta prontidão entre blocos. Uma confirmação ATT indica entrega ao GATT, não conclusão física da escrita.
- O upstream possui sequências específicas de ferramenta, limites, base, pressão, velocidade, aceleração e offset. Seu agrupamento da Cameo 5 com Cameo 4 contém comentários explícitos sobre reutilização de parâmetros. Essas opções exigem validação com caneta e modelo exato; o aplicativo não deve anunciar suporte físico validado a elas.

## Critérios antes de habilitar escrita física

1. Capturar anúncio, modelo/firmware e serviços/características reais da máquina do usuário.
2. Comparar os UUIDs publicados e confirmar a identidade por resposta do equipamento, sem assumir nome do anúncio como prova.
3. Validar origem física, orientação Y/X, posição da ferramenta, base e área mecânica em teste sem caneta, com deslocamentos mínimos.
4. Validar comandos de ferramenta/levantamento e fluxo de estados; confirmar conclusão física, comportamento ao descarregar papel e desconexão.
5. Testar cancelamento e recuperação; pausar transmissão não interrompe movimentos já enfileirados no equipamento.
6. Executar marca pequena em folha de descarte; medir e salvar calibração antes de texto longo.

Até esses testes, descoberta BLE/USB, logs, serialização GPGL, SVG e simulador são recursos de software; não representam certificação de impressão na Cameo 5. Não enviar configurações de lâmina, pressão ou aceleração por suposição. Origem nominal e dimensões do papel não substituem medição do carregamento real.


## Evidência obtida com o usuário

O diagnóstico local enviado pelo usuário identificou os quatro UUIDs documentados e resposta real `CAMEO 5 V1.04` após handshake e `FG`. O endereço BLE não é incorporado ao código nem publicado. Isso comprova comunicação de diagnóstico com uma Cameo 5 original; não comprova movimento ou escrita.

A versão 0.2.0 adiciona consulta `ESC ENQ` e teste opt-in de três movimentos absolutos M, limitado aos pontos (1,1), (2,1), (1,1) mm. Sem caneta/lâmina, sem D nem configuração de ferramenta. Uma resposta desconhecida, timeout ou desconexão impede próximos movimentos; não há retomada automática. A posição física inicial/origem requer conferência pelo usuário. O primeiro teste de movimento foi posteriormente confirmado pelo usuário, conforme registro abaixo.

### Teste físico de movimento confirmado

O segundo diagnóstico enviado pelo usuário na versão 0.2.0 registra a sequência completa `M20,20`, `M20,40`, `M20,20` (todos terminados em ETX), com respostas de estado `0` (pronta) entre as etapas. A característica CONTROL também registra eventos `1` → `0` associados aos comandos. O usuário informou explicitamente: **“o carro moveu”**.

Evidência agora disponível: envio BLE de comandos GPGL M e movimento físico observado em uma Cameo 5 original V1.04. O teste foi concluído sem comando D, e o diagnóstico terminou com estado pronto. O registro confirma movimento observado, não uma medição independente da distância, orientação dos eixos ou origem física.

Ainda pendentes: baixar/levantar a caneta sob carga, executar um traço D, verificar precisão em milímetros, calibração em folha pautada, execução de texto, limites mecânicos e parada/desconexão durante movimento. O resultado não libera automaticamente escrita de texto nem comprova esses itens.


## Bateria experimental 0.3.0

Uma única execução reúne cinco padrões M/D, com movimentos limitados à folha/margens e extensão máxima de 85 × 110 mm. A configuração experimental de caneta usa apenas comandos publicados em `SilhouetteCameoTool` de Graphtec.py na revisão fixada: seleção J1, força FXn,1 (limitada a 1–20) e velocidade !n,1 (limitada a 1–10), FC0,1,1, FE0,1 e FF0,0,1. Não envia profundidade, aceleração nem reset. Isso constitui implementação baseada em fonte verificável, não confirmação física desses ajustes.

Cada comando tem confirmação ATT e consulta de estado, sem repetição de movimento incerto. M levanta no último ponto confirmado antes de atender pausa/interrupção solicitada; falhas de comunicação encerram a sessão sem adivinhar posição nem enviar recuperação. O relatório único distingue envio de cada etapa das observações manuais e registra perfil/calibração, parâmetros, medidas e comunicação com timestamps. O último relatório é salvo localmente durante a execução; uma execução recuperada após fechamento não é declarada concluída. O usuário pode repetir a bateria e corrigir X/Y no mesmo APK.


## Retorno físico da bateria 0.3.0 e otimização 0.3.1

O usuário marcou os cinco padrões como OK em uma Cameo 5 V1.04, usando força 1 e velocidade 10 na escala GPGL. Isso é observação positiva de escrita, levantamento, formas e amostra com acentos. Largura/altura do quadrado e erro X/Y vieram em branco: não são medições de precisão nem uma calibração quantitativa. O usuário relatou escrita muito lenta.

A versão 0.3.0 aguardava pelo menos 100 ms e consultava prontidão após cada M/D, incluindo cada pequeno segmento de glifo. Essa espera independe da velocidade configurada na máquina. A correção 0.3.1 preserva a ordem e todos os segmentos D, agrupa comandos em blocos de até 512 bytes (abaixo dos 1024 publicados em safe_write no upstream), e transmite cada bloco em fragmentos ATT de até 20 bytes, sequenciais e confirmados. Só consulta prontidão antes/depois do bloco; o intervalo de 50 ms é aplicado apenas enquanto o estado é moving, sem espera fixa quando ready.

Todo bloco termina em M no último ponto. Traços muito longos são subdivididos com M de fechamento e retomada na mesma coordenada; nenhuma ligação D é inventada. Pausa/interrupção aguarda o término do bloco atual, em vez de um único segmento. Em falha de escrita ou status desconhecido não há repetição/reinício nem recuperação de posição presumida. O tamanho limitado e a espera por prontidão entre blocos preservam o controle de fluxo. A confirmação ATT continua significando entrega, não observação da marca física.

O app solicita prioridade de conexão BLE alta durante a bateria e restaura a prioridade balanceada ao final; a solicitação não garante um intervalo de rádio específico. O relatório registra duração total (incluindo pausas), blocos transmitidos, escritas GATT confirmadas e consultas de estado, e carrega relatórios anteriores sem métricas. A melhora real na máquina requer uma nova execução; não é inferida apenas do teste de software.


## Retorno 0.3.1 e comparação 0.3.2

O usuário relatou melhora grande e forneceu duração de 22,544 s, 12 blocos, 49 consultas e 213 escritas GATT, com força 1 e velocidade 10. As cinco observações desse relatório vieram como Não avaliado, sem medidas de escala/calibração. Isso comprova uma execução enviada com melhor velocidade percebida, sem substituir avaliação visual ou metrológica.

A versão 0.3.2 remove a consulta que repetia imediatamente o estado pronto após cada bloco; mantém consulta no início, depois de cada bloco e após uma pausa observada. Preserva fragmentos ATT de 20 bytes confirmados, sem assumir MTU maior nem suporte a escrita sem resposta. O stream compacto omite o M sem deslocamento entre dois traços quando o M para o início do próximo traço já faz o levantamento. Todo bloco continua terminando com M no último ponto. Remove somente duplicatas e vértices exatamente colineares na grade nativa de 0,05 mm, preservando reversões, cantos, pontos isolados e a cobertura de tinta; não usa simplificação aproximada de curvas.

O parâmetro de velocidade !n,1 pode chegar a 30 no setup da linha Cameo 4/5 do Graphtec.py fixado. A UI mantém a seleção anterior e oferece comparação explícita de 10/20/30: mesma frase em três pautas, força constante, tempos e avaliações por linha. Velocidades 20/30 continuam experimentais nesta máquina. Após comparação concluída, o runner restaura a velocidade inicial 10; nenhum comando de movimento é reenviado em falha. Não altera aceleração, profundidade ou origem.

O relatório acrescenta tempo por etapa, velocidade efetiva da etapa, bytes de comandos, tempo de espera por ACK e tempo total das consultas de estado. O tempo das consultas inclui seus ACKs e sobrepõe a métrica de ACK; não devem ser somados como componentes independentes. A nova medição permitirá separar latência do transporte de ganho mecânico sem prometer que velocidade 30 será três vezes mais rápida.


O log de cada pacote continua sendo mantido em memória, mas atualizações de Compose/relatório são publicadas nas fronteiras de bloco/etapa e ao encerrar, em vez de copiar o relatório crescente e reconstruir a tela a cada pacote. O último bloco em andamento pode não estar no relatório persistido se o processo for encerrado abruptamente; a execução recuperada permanece interrompida, sem retomada automática. Em término normal ou falha capturada, o journal é descarregado no relatório antes de salvar as métricas finais.


## Página do editor e interface 0.4.0

O pedido de otimizar MTU foi cancelado antes de alterações no código: o transporte permanece o da 0.3.2. O usuário pediu simplificação e escrita de texto próprio. A nova interface inicia em Feia/Rápido, oculta diagnósticos, conecta/identifica automaticamente após escolha da Cameo ociosa e envia a página selecionada do documento planejado. O runner de blocos foi extraído sem mudar o envio de ACK/GPGL; a bateria mantém seu limite pequeno, e `CameoPageJob` valida separadamente o papel completo, margens, finitude, até 200 mil pontos e número de página antes de enviar.

Não há envio automático de outras páginas ou repetição de trabalho parcial. Cada próxima folha requer seleção/ação do usuário. Um estado conhecido de mídia descarregada/ocupado antes de começar não envia comandos de ferramenta ou movimento, mantendo a conexão para correção. Estado desconhecido, falha ou timeout continuam encerrando a sessão. A escrita completa em papel deve ser observada pelo usuário antes de ser declarada fisicamente validada.
