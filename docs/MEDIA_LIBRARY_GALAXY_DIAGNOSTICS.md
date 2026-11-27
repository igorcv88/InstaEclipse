# Direct 454 no Galaxy

Use o APK assinado correspondente ao head do PR #9 e o Instagram 454.0.0.0.55 (386005100). Anote a versão da prévia e o SHA do commit e do APK publicados em Actions. Instale como atualização e reinicie o processo do Instagram depois de habilitar o módulo no LSPosed. O teste usa duas contas próprias ou autorizadas, em uma conversa de teste já lida na fase de preparação. Separe uma foto recém-enviada, uma foto e um vídeo antigos em outra página, um carrossel com duas partes e uma segunda conversa para o controle de escopo. Desligue Visualização única ilimitada. Nenhuma etapa exige abrir mensagem efêmera.

## Coleta única no Termux

O comando precisa do `su` autorizado para o Termux. Ele habilita o trace somente durante a coleta, não limpa o buffer global e seleciona os dois tags usados para as etapas e a rodada. Execute o bloco no Termux, deixe a sessão aberta e use o Android para seguir a matriz. Termine com Ctrl+C. Os dados ficam no diretório impresso. `sha256sum` pode ser aplicado também ao APK antes da instalação para confrontá-lo com o Actions.

```bash
(
set -o pipefail
stamp="$(date +%Y%m%d-%H%M%S)"
out="$HOME/instaeclipse-direct-$stamp"
mkdir -p "$out"
{
  date -Iseconds
  for app in com.instagram.android ps.reso.instaeclipse; do
    printf '\n%s\n' "$app"
    su -c "/system/bin/dumpsys package $app" |
      grep -m 2 -E 'versionCode=|versionName='
  done
} > "$out/versoes.txt"
trace_original="$(su -c '/system/bin/getprop log.tag.IEDirectTrace' | tr -d '\r')"
case "$trace_original" in
  VERBOSE|DEBUG|INFO|WARN|ERROR|ASSERT|SUPPRESS|V|D|I|W|E|A|S) ;;
  *) trace_original='' ;;
esac
trap 'su -c "/system/bin/setprop log.tag.IEDirectTrace \"$trace_original\""' EXIT
su -c '/system/bin/setprop log.tag.IEDirectTrace DEBUG' || exit 1
printf 'Coletando em %s. Termine com Ctrl+C.\n' "$out"
su -c '/system/bin/logcat -T 1 -v threadtime -s IEDirectTrace:D IeDirectConsolidation:I' |
  tee "$out/direct.log"
)
```

Se o Termux for encerrado à força, desative o trace com `su -c '/system/bin/setprop log.tag.IEDirectTrace INFO'`. Em uma segunda sessão, para registrar a ordem dos passos, use `date -Iseconds` a cada ação e anote o nome do passo (A, B etc.). Para filtrar um alias já identificado, use `grep -F 'part=ALIAS' "$out/direct.log"`. Para interpretar o status HTTP, a linha `cdn_http` fica entre `transfer_start` e `transfer_response` no mesmo PID/TID. Os aliases permanecem iguais entre host e módulo no mesmo dia UTC. A linha do carrossel inclui alias dos filhos e do pai. O log não inclui IDs, URLs, caminhos, conteúdo ou nomes de conversas.

## Matriz

| Passo | Ação | Evidência esperada |
|---|---|---|
| A. Controle de renderização | Ligue o atalho da biblioteca e deixe consolidação desligada. Use **Salvar** em uma foto convencional de teste, abra a biblioteca da mensagem e importe conscientemente o arquivo correto. Feche a conversa e reabra em modo avião. | Mesmo alias em `save_selection`, `inline_lookup result=preview` e `inline_bind result=applied`. Tocar a miniatura abre a biblioteca da mensagem. Recolha uma captura visual somente se a mídia de teste puder ser compartilhada. |
| B. Rebind e escopo | Ainda offline, role a foto para fora da tela, volte e alterne para a segunda conversa. Repita após encerrar o Instagram e a biblioteca. | A miniatura aparece apenas no bind da mensagem original. Sem cópia, conflito, conta incorreta ou escopo diferente, preservar o balão nativo. |
| C. Controle recente | Volte à rede. Habilite consolidação e reabra a primeira conversa. Aguarde a captura e, em seguida, execute a varredura manual antes de rolar. | Descoberta, fila local, IPC e decisão do catálogo são observáveis separadamente. O controle só está guardado quando há `persisted` ou quando uma cópia anterior inequívoca abre offline. |
| D. Foto anterior | Role até a primeira foto antiga. Aguarde a rodada. Em sequência, abra **Mídias compartilhadas** e volte para a conversa. Repita a varredura manual. | A ordem separa `native_page`, `native_row_batch`, `constructor`, `builder`, `shared_media` e `local_db`. Um mesmo alias com `host_queue result=DUPLICATE` indica que aquela observação não acrescentou oferta naquele momento. A primeira etapa ausente ou recusada localiza o defeito de cada foto. |
| E. Vídeo anterior e carrossel | Role até ambos no mesmo teste, registrando a ordem. Abra a galeria da mensagem pelo atalho quando ele estiver disponível. | Um vídeo completo deve abrir offline e reproduzir até o fim. Para o carrossel, confira vínculo e hash por parte no catálogo e ordem dos índices na galeria da mensagem. O bind de foto convencional não substitui uma parte nativa cujo ID difere do pai. |
| F. Falha e retomada | Em outra mídia convencional de teste, interrompa a rede durante a transferência. Após a janela de tentativas, religue e retome em **Gerenciar → Cobertura do Direct**. | Nenhum `persisted` para dados parciais. Itens posteriores válidos não dependem de concluir o item antigo. URL nova de mesma identidade deve aparecer como tentativa de nova versão. Cópia já guardada permanece offline. |
| G. Controle de recibo | Na conta remetente de teste, observe o estado de uma mídia efêmera que permaneceu fechada durante o teste. | Sem alteração no estado dessa mensagem. Os passos anteriores operam em mídia convencional de teste e em arquivos fornecidos localmente. |

Envie `versoes.txt`, `direct.log` e a ordem das ações da mesma coleta. Para D e E, indique quais miniaturas abriram offline na biblioteca da conversa e quais URLs ficaram indisponíveis segundo o trace, sem incluir as URLs. A leitura dos principais resultados é esta:

| Última fase observada | Interpretação |
|---|---|
| `discovery` sem `host_queue` ou `ipc` | Identidade, tipo, serialização ou schema. Compare rota, concordância de escopo e descarte. |
| `host_queue` sem `catalog_offer` | Captura sem fonte, descarte/limite antes de IPC, recusa de provider ou evento duplicado. Cruze com `ipc` e `ipc_error`. |
| `catalog_offer disposition=eligible` sem `transfer_start` | Trabalho pendente no módulo. Veja a pausa, os totais do worker e a ordem de disparo. |
| `transfer_start` sem `transfer_complete` | Resposta indisponível, corte do corpo, cancelamento ou erro de rede. Confira HTTP e `outcome`. |
| `transfer_complete` sem `validated` | Bytes recebidos, mas inválidos ou fora dos tipos aceitos. |
| `validated` sem `persisted` | Falha após validação, por exemplo quota, armazenamento ou transação do catálogo. |
| `persisted` | O importador retornou após localizar ou armazenar a cópia. `status=ALREADY_PRESENT` reconfirma a existente; os demais status correspondem a armazenamento ou reparo. `outcome=AMBIGUOUS` mantém arquivos sem escolher uma cópia para a mensagem. Abra offline no escopo correto. |

## Estado da implementação

O agendamento associa o resultado que falhou à versão da fonte lida. Um resultado antigo não baixa a versão mais recente da fila. A passada continua após falhas individuais e dá prioridade a trabalho novo no próximo lote. O catálogo mantém as chaves históricas de carrossel e resolve explicitamente o pai e seus índices no escopo certo. A fila mescla fontes complementares de uma mesma representação; linhas incompletas não removem fontes válidas já gravadas.

A visualização inline usa apenas o bind/unbind convencional de 454. O DEX define as assinaturas de bridge `X.0ThI.AWs(X.0YmP, X.0mbl)` e `Hjg(X.0YmP)`, que usam o holder `0TiJ`. `0BYS` delega `CpM()` a `03QJ.A04`, com a thread em `A05` e a direção em `A09`; a sessão vem de `0ThI.A04`. Um ID de servidor distinto do client context, tipo `MEDIA`, e conta/thread iguais às da tela são exigidos. A imagem só é anexada se existir cópia local exata, própria ou explicitamente vinculada. A consulta é assíncrona, e o rebind, detach e unbind invalidam respostas antigas. Uma cópia manual é suficiente para testar o bind, sem consolidação.

A coleta mede a contribuição independente de cada observador existente. O fallback abandonado para activity em primeiro plano, sem chamadores, e o relatório do worker em memória, que nenhuma tela lia, foram removidos. Os observadores sobrepostos continuam até que haja medida por rota no aparelho. O bind de balões efêmeros ainda depende de identificar uma rota com os mesmos contratos de identidade e reciclagem.

Os testes de JVM usam URLs e arquivos de teste, com interleaving controlado por callback e sem chamadas ao Instagram. Os testes não reproduzem o host Android real. A descoberta final da foto antiga e a apresentação inline ficam pendentes de validação no Galaxy.
