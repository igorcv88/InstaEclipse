# Handoff da biblioteca de mídias, etapa 2

## Decisões vinculantes do responsável (mais recentes primeiro)

-1. **2026-11-27, objetivo final.** Tornar definitivas as mídias efêmeras: toda visualização única e com repetição cujo modelo traga URL de CDN é baixada e guardada na biblioteca, com o modo original marcado, independentemente de remetente, estado de visualização ou da opção "Visualização única ilimitada". Depois, a cópia substituirá o balão efêmero pela estética de foto definitiva. O laudo técnico de 27/11 serve só de guia técnico, não de regra. Substitui as restrições de visualização única das entradas 0 e 1.

0. **2026-11-27, após o teste físico.** Comportamento esperado igual ao WhatsApp: guardar automaticamente as mídias que chegam e que o Instagram mostra, vinculadas à mensagem, para sempre e offline. Visualização única e com repetição são guardadas quando o usuário as abre no Instagram (ou quando são dele), nunca antes. Substitui a restrição de "só por ação explícita" da entrada 1 para mídias convencionais e permanentes; a proibição de consumir visualização única continua.

1. **2026-11-27.** Não executar automaticamente operações que consumam visualizações únicas ou alterem irreversivelmente o estado das mensagens no servidor. Separar a consolidação histórica de arquivos elegíveis da abertura de mensagens efêmeras. Validar primeiro só com mídias de teste próprias ou autorizadas, sem operações em massa sobre conversas reais. Biblioteca, associação individual e integração visual são validadas de forma independente, e nenhuma depende de alterar o estado remoto. Preservar a disponibilidade nativa e não tratar a apresentação inline como garantia contra perda. **Revoga** a aceitação de prefetch da entrada 3.
2. **2026-11-26.** Compose: arquitetura híbrida. Investigar a integração visual com Compose para mensagens convencionais e cópias próprias ou autorizadas, atrás de flag independente, com validação de identidade, recomposição, reciclagem, paginação e troca de contexto. Apresentação nativa e galeria própria permanecem como fallback. A biblioteca não depende da integração visual.
3. ~~2026-11-26. Prefetch de visualização única aceito.~~ Revogado pela entrada 1.
4. **2026-11-26.** O corpus do APK 454 fica no release rascunho `apk-454-corpus` do repositório, fora do Git.

## Estado em 2026-11-27

Feito, com evidência estática e testes JVM:

- Catálogo v2 com cobertura por identidade e por conversa (`DirectCoverage`), captura (`DirectCapture`) e consolidação (`DirectConsolidation`).
- Leitura somente-leitura de `direct.db` no processo do Instagram (`DirectStoreScanner`), oferta por IPC (`direct_offer`), worker no módulo (`DirectConsolidationWorker`) e relatório **Cobertura do Direct**.
- Flag própria `directMediaConsolidation`, desligada por padrão. Ações explícitas por conversa e por conta.
- Inventário estático em [MEDIA_LIBRARY_DIRECT_454_INVENTORY.md](MEDIA_LIBRARY_DIRECT_454_INVENTORY.md).

Não feito:

- **Miniaturas nos balões.** O ponto de bind das linhas View (`X.0ThI.AWs` / `Hjg`, identidade em `X.0B79.Brl()`) está mapeado, mas o hook não foi escrito. Os balões continuam nativos.
- **Compose.** Nenhuma rota Compose de balão de mídia foi localizada nesta inspeção. A flag Compose não existe ainda.
- **Captura no momento em que o próprio usuário abre uma mídia efêmera.** Não implementada. Visualização única só é contada.
- **Consolidação automática na primeira abertura.** Deliberadamente fora, pela decisão 1. A consolidação só começa por ação explícita. Depois da validação física, uma rodada automática pode reutilizar o mesmo caminho.
- **Fluxo de páginas ao vivo** (hook de desserialização). O cache local cobre o que o cliente já guardou; mensagens novas entram no cache e são vistas na próxima leitura explícita.

## Triagem da revisão independente (2026-11-27)

Revisão estática externa de `7beb39d`. Cada item foi conferido no código antes de qualquer mudança.

| Item | Veredito | O que mudou ou por que não |
|---|---|---|
| Pausa não persistente | Procedente | Pausa gravada em `meta`. Retomada automática (reinício do módulo) nunca a desfaz; só ação explícita (nova leitura ou **Continuar cópias**) |
| Flag desligada durante a execução | Procedente | O critério de parada inclui a flag e é avaliado entre itens e a cada leitura |
| Fila não restrita à conversa | Improcedente como defeito | Toda entrada da fila veio de ação explícita do usuário e é retomada de propósito. Contas e conversas ficam isoladas na identidade e no catálogo |
| Importado continua contado depois de perdido | Procedente | O relatório separa estado histórico de disponibilidade atual (disponível, danificado ou ausente, na lixeira). Cópia perdida fora da lixeira impede "completa" e é refeita a partir da mesma mensagem na próxima leitura com URL nova. Cópia na lixeira é decisão do usuário e não é baixada de novo |
| Checkpoint do `ImportBatch` na finalização | Improcedente | Morte do processo em qualquer ponto da finalização deixa um lote que a próxima execução conclui de novo de forma idempotente; o progresso órfão é descartado pelo `id` |
| Limites do `ExportFolder` | Limite documentado | Mantido. A recusa é explícita e o relatório diz o motivo |
| Timeout do `LibraryLock` entre processos | Procedente, risco baixo | Espera pelo lock de arquivo limitada ao mesmo prazo. Hoje só o processo do módulo abre a biblioteca |
| Viewer acessa SQLite na thread principal | Procedente, não corrigido | Fora do escopo desta rodada; registrado |
| Pedido perdido enquanto o worker termina | Procedente | Pedido marcado em flag atômica; o worker volta a rodar se chegou pedido depois da última verificação |
| Item abandonado após cinco falhas | Procedente | **Continuar cópias** zera as tentativas das falhas |
| Arquivo incompleto recusado em definitivo | Procedente | `INCOMPLETE_MEDIA` de uma captura vira falha transitória com limite de tentativas. O download confere `Content-Length` |
| Página recusada contada como oferecida | Procedente | Só conta o que o módulo aceitou; recusa interrompe a leitura com erro. Achado adicional: páginas com carrosséis podiam passar do limite de 200 partes e eram recusadas inteiras; agora são divididas. Itens repetidos no cache são desduplicados |
| Associação manual sem prova de conteúdo | Improcedente como defeito | É afirmação explícita do usuário. Uma segunda cópia diferente para a mesma identidade torna a busca ambígua e nunca escolhe uma |
| Pausa só entre arquivos | Procedente | A pausa interrompe a transferência em andamento, não grava resultado e não consome tentativa |
| Truncamento da leitura sem aviso | Procedente | Limites ampliados e aviso explícito quando atingidos |
| Galeria sem atualização reativa | Procedente, não corrigido | A galeria recarrega ao abrir; registrado |
| Cache de miniaturas sem geração | Improcedente | O cache usa o caminho do blob, que é o SHA-256 do conteúdo |
| Backup sem cobertura v2 | Improcedente para o objetivo | O backup guarda cópias e identidade da mensagem. A cobertura é derivada e é refeita pela próxima leitura, que encontra as cópias como já presentes |
| Janela de 201 itens no visualizador | Procedente, não corrigido | Registrado |

Testes: `DirectReviewFixesTest` (7) e ajustes em dois testes existentes. Seis mutações dessas correções derrubam pelo menos um teste. A corrida do worker é Android e não tem teste JVM.

## Próximos passos

1. Executar os itens 6 e 7 de [validação](MEDIA_LIBRARY_DIRECT_454.md#validação) numa conversa de teste e anexar logs.
2. Com o dump real, ajustar `DirectStoreRow` se o aninhamento diferir.
3. Implementar o hook de bind de `X.0ThI` atrás de flag própria, com token por bind (inclusive binds sem mídia), restauração compare-and-restore e leitura do arquivo por `openFile` somente-leitura com escopo de conta.
4. Procurar rotas Compose de mídia em 454 por `ComposeView` dentro de `direct_thread` antes de escrever qualquer hook Compose.
