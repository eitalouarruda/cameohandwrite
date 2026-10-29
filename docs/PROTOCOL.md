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
