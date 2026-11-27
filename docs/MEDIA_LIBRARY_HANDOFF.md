# Handoff da biblioteca de mídias, etapa 2

## Decisões vinculantes do responsável (mais recentes primeiro)

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

## Próximos passos

1. Executar os itens 6 e 7 de [validação](MEDIA_LIBRARY_DIRECT_454.md#validação) numa conversa de teste e anexar logs.
2. Com o dump real, ajustar `DirectStoreRow` se o aninhamento diferir.
3. Implementar o hook de bind de `X.0ThI` atrás de flag própria, com token por bind (inclusive binds sem mídia), restauração compare-and-restore e leitura do arquivo por `openFile` somente-leitura com escopo de conta.
4. Procurar rotas Compose de mídia em 454 por `ComposeView` dentro de `direct_thread` antes de escrever qualquer hook Compose.
