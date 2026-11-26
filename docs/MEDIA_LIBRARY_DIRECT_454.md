# Biblioteca no Direct 454

## Uso

Com **Atalho da biblioteca no Direct** ativo, use **Salvar** no menu de uma foto ou vídeo convencional. Quando a versão e as identidades são confirmadas, o InstaEclipse oferece abrir a biblioteca daquela mensagem. Ali é possível selecionar um arquivo, vincular uma cópia ainda sem mensagem ou abrir a miniatura de uma cópia inequívoca. O visualizador usa o arquivo guardado no InstaEclipse. O fluxo nativo de **Salvar** continua disponível. O indicador de reprodução na conversa, por si só, não inicia uma importação.

Em **Gerenciar → Importar pasta de exportação**, escolha uma pasta extraída do export do Instagram ou uma pasta com arquivos fornecidos para importação. É possível escolher a pasta da conversa desejada dentro do export. Ao iniciar pela biblioteca de uma conversa, o lote usa o escopo daquela conversa. Na biblioteca principal, o lote fica sem conversa. O conteúdo da pasta e os anexos do export não fornecem, por si sós, um vínculo comprovado com uma mensagem.

O seletor da pasta descobre candidatos antes da confirmação. A validação lê os bytes de cada candidato e passa pelo importador já usado para arquivos avulsos. A seleção avulsa mantém o limite de 200 arquivos. Uma pasta aceita até 5.000 candidatos e 50.000 entradas examinadas, com erro quando ultrapassa os limites. Metadados de exportação e outros arquivos fora dos tipos aceitos ficam fora da seleção. O relatório mostra quantos candidatos foram processados, importados, já presentes, recusados e mantidos sem vínculo com mensagem. Ele também mostra as primeiras 20 falhas individuais. As contagens se referem à fonte escolhida.

Há um lote pendente por instalação. A sessão guarda a lista de documentos e o checkpoint em arquivos separados e retoma depois do último resultado confirmado. O passo idempotente cobre uma interrupção entre o commit de um item e o registro do progresso. Se o Direct fechou ou a mensagem saiu da tela, volte à biblioteca principal e use **Gerenciar → Retomar importação do Direct**. O resultado do último lote concluído é consultável no escopo correspondente. Coleções grandes consomem espaço no armazenamento privado.

## Identidade e evidências estáticas

APK inspecionado **454.0.0.0.55 / 386005100**, SHA-256 `3419bb746e9f78cfdcca3794621e63fcde7317f81dc3b36ba2fe6b75a992b4c8`. A base do PR é a etapa 1 em `09fbe3fa6d35e0d8f09333895269b443c34358c7`, derivada do WaEnhancerXCommunity PR #176, `36b6593c5343bd73a7ba7921c66b0fe42055d6d6`.

| Observação em DEX | Uso |
|---|---|
| `DirectThreadKey` contém os ramos `thread:` e `recipients:` | Reutiliza o resolvedor de conversa que exige ID de thread |
| `X.01hK.A0o` retorna o campo `mId` e o diagnóstico distingue `mClientContext` | O caminho novo exige ID de mensagem em vez de usar o ID local como alternativa |
| `X.01hK.A0d` constrói `DirectMessageIdentifier` com tipo, ID da mensagem e ID local. O ID de mensagem é gravado em `A02` no identificador | Confere o ID do modelo contra o identificador do callback |
| `X.01hK.A26(User)` compara o ID do usuário de referência ao remetente do modelo | Define a direção comparando remetente e conta ativa |
| `X.0QtM.A08` recebe Activity, View, UserSession, enum de ação, modelo, DirectThreadKey e MessageIdentifier. O enum contém `SAVE_MEDIA` | O hook revalida conta, conversa e ID na ação selecionada. Só admite o tipo explícito `MEDIA` |
| `X.0KWO` declara nome original `DirectThreadSharedMediaFragment` e infla `0x7f0e0e74`. O campo de thread é `A03` e a vista nativa usa `shared_media_list` | Oferece atalho para a galeria da conversa em raízes compatíveis. Revalida identidade no clique e remove o botão com a view do fragmento |

A associação é explícita e só acontece após validação da seleção nativa e escolha da cópia pelo usuário. Confirmações tardias são descartadas se o contexto mudou. Duas cópias divergentes com a mesma identidade continuam na galeria da conversa e a tela filtrada da mensagem deixa de escolher uma miniatura. O catálogo, os recibos e as notificações do Instagram ficam fora do fluxo. Uma página nova ou a ausência de uma mensagem não altera o catálogo local.

A miniatura específica da mensagem aparece na galeria do módulo. Falta confirmar o componente que recebe a identidade durante o bind de cada balão nas rotas Android Views e Compose. A renderização nos balões permanece nativa. O atalho de mídias compartilhadas mantém a galeria própria como fallback e não substitui o adaptador do Instagram.

## Validação

Os testes de SQLite exercitam páginas simuladas em ambas as direções, repetição de página, IDs reconstruídos, troca de escopo, colisão e leitura de uma cópia ausente da página mais recente. O teste Android reconstitui o importador e o SQLite após uma pausa e verifica o checkpoint, o relatório e a retomada na fonte seguinte com arquivos sintéticos. Os testes de recibos e notificações da suíte existente tratam a política local, sem afirmar o comportamento do servidor.

O relato físico da etapa anterior foi de uma mídia reproduzível que não entrou na biblioteca. Naquela versão, o importador só recebia entradas do seletor. Para validar a etapa 2 em aparelho, use conta e mídias de teste no Instagram 454:

1. Use **Salvar** em duas mídias convencionais consecutivas e associe arquivos diferentes. Confira as miniaturas e o acesso ao visualizador. Confirme que os itens continuam acessíveis pelo módulo em modo avião.
2. Repita após force-stop, reinício, refresh e paginação nas duas direções. Alterne conversa e conta durante abertura do atalho. Reproduza o mesmo item com a UI reconstruída.
3. Abra **Mídias compartilhadas** e confirme o atalho, a grade e o visualizador nativos. Confira também a galeria pelo menu da conversa.
4. Selecione uma pasta extraída de teste. Interrompa o lote, reinicie o app e retome pela biblioteca principal. Confira as contagens e os arquivos mantidos sem vínculo com mensagem.
5. Leia uma DM, saia, reabra e atualize. Confira o recibo e a ausência de notificações repetidas. Compare com o atalho ligado e desligado.
