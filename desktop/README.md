# Cameo Handwrite para Linux

Execute `./dev.sh` na pasta do projeto. A primeira execução instala PySide6 e Bleak em um ambiente local; depois funciona offline.

Cole o texto, confira a prévia e pressione **Imprimir**. Deixe uma única Cameo 5 ligada e visível por Bluetooth. Carregue a folha A4 alinhada à origem e use caneta no suporte 1, sem lâminas. A identificação do modelo e o estado carregado são verificados antes de escrever.

Em **Medidas e avanço…**, ajuste largura/altura do papel, margens, primeira pauta (início Y), avanço entre linhas, altura das letras, espaço entre palavras e correção X/Y. Tudo em milímetros, em passos de 0,1 mm. X positivo move à direita; Y positivo para baixo. A prévia mostra as pautas e margens e usa os mesmos trajetos enviados. Os valores iniciais são A4, margens 25/15 mm, primeira pauta a 20 mm, pautas de 8 mm e letras de 4 mm. Força 1 e velocidade nativa 10 continuam fixas. O desenho usa os arquivos Hershey licenciados do projeto, traços únicos e variação determinística. Caracteres ausentes bloqueiam o trabalho.

O envio para após cada folha. Carregue a próxima, selecione **Folha** e pressione **Imprimir**. **Pausar / cancelar** termina o traço em andamento antes de parar. O verde na prévia representa a região anterior ao ponto selecionado de retomada; não confirma presença de tinta.

O último traço confirmado é salvo em `~/.local/share/cameohandwrite/linux-job.json`. Reabra o programa para recuperar texto e progresso. Para ajustar a retomada após erro, clique no traço da prévia ou use o campo numérico. Mantenha a folha na mesma posição e confira o ponto antes de imprimir. Uma desconexão pode deixar parte do último traço desenhada: a retomada é manual e pode repetir essa parte. Alterar o texto gera outro trabalho e zera o progresso.

BlueZ deve estar ativo. Esta versão usa os UUIDs e comandos já testados no Android, fragmentos BLE confirmados de 20 bytes e blocos com caneta levantada de até 512 bytes. A comunicação física pelo Linux ainda precisa de teste na Cameo; a abertura da interface, paginação e determinismo foram verificados por software. O motor Python ainda não produz exatamente as mesmas variações geométricas do Android.

Escolha a fonte no topo da janela: **Muito feia** (padrão de trabalhos novos), **Feia**, **Cursiva** ou **Anterior**. As duas primeiras misturam glifos Hershey de forma e cursiva, com deformação determinística dos próprios traços. Trocar a fonte recalcula a paginação e zera o progresso. Trabalhos antigos reabrem com **Anterior** para preservar os trajetos usados na retomada. A fonte escolhida fica salva junto ao texto.

As medidas são salvas junto ao trabalho e ao progresso. Trabalhos antigos usam os mesmos valores iniciais. Alterar as medidas recalcula o documento e zera a retomada; se já houve envio, o app pede confirmação antes de aplicar. Isso evita continuar sobre uma folha usando coordenadas diferentes. A correção X/Y muda a posição dos traços em relação às pautas da prévia, sem mover a folha virtual. O início é medido a partir da origem carregada da Cameo; o app não detecta fisicamente a borda nem as linhas impressas.
