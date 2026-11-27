# Inventário estático do Direct no Instagram 454

APK **454.0.0.0.55 / 386005100**. `base.apk` SHA-256 `3419bb746e9f78cfdcca3794621e63fcde7317f81dc3b36ba2fe6b75a992b4c8`, extraído do corpus `Instagram_454.0.0.0.55.tar.gz` (SHA-256 `98f61046fb62a109f0000a30b9ef022839fa2153e77edeb228e6a3802d672657`). O corpus fica fora do Git.

Método: `dexdump` 35.0.0 para localizar definições e chamadas; jadx 1.5.1 com `--single-class` para ler as classes citadas; `aapt2 dump resources` para IDs. Todo o conteúdo deste documento é **evidência estática**. Nada aqui foi observado em aparelho.

Níveis de confiança: **A** = lido no código e coerente entre duas classes; **B** = lido no código, uso em tempo de execução não confirmado; **C** = inferido, depende de aparelho.

## Armazenamento local do Direct

| Fato | Classe | Confiança |
|---|---|---|
| Banco `databases/direct.db` (ou `direct_side_panel.db` no modo painel lateral), `SQLiteOpenHelper` versão 15 | `X.01ab` | A |
| Ao falhar a abertura, o próprio Instagram apaga `direct.db` | `X.01ab.A00` | A |
| Tabela `messages(_id, user_id, server_item_id, client_item_id, thread_id, recipient_ids, timestamp, message_type, text, message)`, índice `(user_id, thread_id, timestamp DESC)` | `X.0sc5`, migrações `X.0yfA`…`X.0yex` | A |
| `user_id` = `UserSession.userId`; `server_item_id` = `X.01hK.A0o()`; `thread_id` = `DirectThreadKey.A00`; `timestamp` = `X.01hK.A0J()` em microssegundos; `message_type` = `X.01iJ` (`media`, `raven_media`, `visual_media`…); `message` = JSON do modelo | `X.01gX.A0A` | A |
| `message` é JSON gerado por `X.01hN`: `item_id`, `item_type`, `user_id` (remetente), `timestamp`, `timestamp_in_micro`, `media`, `raven_media`, `visual_media`, `view_mode` | `X.01hN`, `X.0DEn.A0O` ("Error creating json string") | A |
| `Media` é serializada por tabela de campos genérica; `image_versions2`, `video_versions`, `candidates`, `carousel_media`, `media_type` existem como nomes de campo | `X.05jz.A0E`, `X.05kd` | B. O aninhamento exato exige um dump de linha real |
| O banco guarda o subconjunto que o cliente carregou. Não há evidência de histórico completo local | `DirectSQLiteDiskIO` (`num_threads_saved_*`, `inbox_clear_*`) | B |
| Banco Room `mdcore_messages(message_id, thread_id, timestamp, offline_threading_id, filter_tags, data BLOB)` e `mdcore_message_list_pagination_cursors(thread_id, end_cursor, oldest_timestamp_ms, has_more_messages)` | strings DEX | C. Não usado: arquivo, formato de `data` e uso em 454 não confirmados |
| Cache de imagens `IgDiskCache` com journal e metadados; cache de vídeo segmentado | strings DEX | C. Não usado: um arquivo de cache não comprova a mensagem e o vídeo não é um MP4 completo |

## Modelo de mensagem

| Fato | Classe | Confiança |
|---|---|---|
| `X.01hK extends X.0DKm` é o modelo da mensagem | `X.01hK` | A |
| `0DKm.A0t` = ID do servidor; `0DKm.A0o` = client context; `A0o()` devolve `A0t` | `X.01hK.A0o` | A |
| `A0d()` = `new DirectMessageIdentifier(A0V tipo, A0t, A0o)` | `X.01hK.A0d` | A |
| `0DKm.A0T` = `DirectThreadKey`; `A0V` = tipo `X.01iJ`; `A1D` = remetente; `A1E` = view mode | `X.0DKm` | A |
| `01hK.A0V` = `Media` convencional; `A0J` = mídia visual (efêmera); `A0X` = `raven_media` | `X.01hK`, `X.01hN` | A |
| `A1v()` é verdadeiro quando o view mode é `once` ou `replayable` | `X.01hK.A1v` | A |
| `A0J()` = timestamp do servidor em µs (`0DKm.A0h`, com `A0l` como alternativa) | `X.01hK.A0J` | A |
| `DirectThreadKey.A00()` formata `thread:` + `A00`, ou `recipients:` + lista | `DirectThreadKey` | A |

O ID usado pelo `CurrentConversation` (`thread:`), o `thread_id` de `direct.db` e o `DirectThreadKey` da ação Salvar são o mesmo campo. O `server_item_id` de `direct.db` e o ID do `DirectMessageIdentifier` vêm do mesmo `A0t`.

## Renderização das linhas de mídia

| Fato | Classe | Confiança |
|---|---|---|
| `layout/direct_media_message` = `0x7f0e0431`; `direct_visual_message` = `0x7f0e0506`; `direct_multi_media_message` = `0x7f0e0436`; `direct_visual_thumbnail_message` = `0x7f0e050a` | `aapt2` | A |
| `X.0ThI` ("MediaContentDefinition") infla `0x7f0e0431` em `B6O`, faz bind em `AWs(X.0TiJ, X.0BYS)` e unbind em `Hjg(X.0TiJ)`. Android Views em RecyclerView | `X.0ThI` | A |
| ViewHolder `X.0TiJ`: `A08` = `RoundedCornerMediaFrameLayout`, `A03` = `IgProgressImageView` | `X.0ThI.B6O` | A |
| View model `X.0BYS extends X.0B79`; `Brl()` devolve o `MessageIdentifier` e `BrO()` o tipo | `X.0B79` | A |
| `0x7f0e0506` é inflado por `X.02vL`; `0x7f0e0436` por `X.03BS`; `0x7f0e050a` por `X.0ThE` | dexdump | B |
| Rotas Compose para balões de mídia | Não localizadas nesta inspeção. A linha de mídia convencional observada é View | C |

## O que é usado no código

- `DirectStoreScanner` lê `direct.db` em `OPEN_READONLY`, somente quando a versão é 15. Só lê as linhas da conta e da conversa abertas. As linhas `media`, `raven_media` e `visual_media` são consideradas.
- `DirectStoreRow` confere `item_id` e `item_type` do JSON contra as colunas e exige o remetente. Linhas contraditórias são recusadas e contadas.
- As mídias de visualização única e com repetição são registradas como descobertas e ficam `UNAVAILABLE` com `view_once_not_fetched`. A URL delas nem é enviada ao módulo.
- Para mídias convencionais, o módulo faz um GET HTTPS da URL de CDN que o modelo já contém (`*.cdninstagram.com`, `*.fbcdn.net`), sem cookies e sem chamar hosts da API. Isso não altera o estado da mensagem no servidor. É uma requisição de rede adicional e **não** uma leitura do cache de bytes do Instagram.
- O hook visual por linha (`X.0ThI.AWs` / `Hjg`) está mapeado e **não foi implementado** nesta etapa. A apresentação nos balões continua nativa.

## Evidência física (Galaxy, 2026-11-27)

- `databases/` não contém base de histórico do Direct além de `direct.db`. `msys_temp_database_file_*` têm 0 bytes. `reverb_db_*` (`local_message_persistence_store`) é do Messenger criptografado. Conclusão: o Instagram 454 não guarda o histórico de conversas comuns no aparelho.
- `direct.db` tinha 4 linhas de mídia, todas `raven_media`, três de uma conversa e uma de outra. Todas com `visual_media.view_mode = permanent` ("Manter na conversa"). O parser as classificou corretamente como capturáveis; nenhuma visualização única foi baixada.
- A varredura de `direct.db` cobre só esse subconjunto. A captura passou a ser feita quando o Instagram monta a linha da mensagem (seção abaixo).
- `DirectLibraryHook.hookMediaAction` falhou ao instalar ("message action unavailable"). A causa não aparece no log antigo; o log agora inclui a exceção.

## Captura ao vivo

| Fato | Classe | Confiança |
|---|---|---|
| `X.0BMD` reúne construtores estáticos de linhas do Direct `(Context, UserSession, X.017Y, X.01Fo, X.02NI) → X.0mbl`; um deles cria o modelo da linha de mídia `X.0BYS` | dexdump | A |
| `X.01Fo.A0y` é o modelo `X.01hK` | dexdump | A |
| `X.01hN.A00(X.0B6n, X.01hK)` serializa o modelo no mesmo JSON de `direct.db`; `X.02bf.A00` (`X.02bg`) cria o gerador com `A01(Writer)` | dexdump | A |
| `UserSession.userId` é o ID da conta; `X.0248.A1a` compara remetente e conta assim | bytecode | A |
| Mídia visual traz `seen_user_ids`, `seen_count`, `url_expire_at_secs` e `view_mode` | `X.01To` | A. Conteúdo de `seen_user_ids` depois da abertura pendente de aparelho |

`DirectLiveCapture` intercepta esses construtores, serializa o modelo de mensagens `media`, `raven_media` e `visual_media`, aplica o mesmo `DirectStoreRow` e oferece ao módulo em lotes de 1,5 s, uma vez por mensagem e estado. Visualização única e com repetição só recebem URL quando a conta é a remetente ou já aparece em `seen_user_ids`.

### Evidência física da captura ao vivo (2026-11-27)

- `direct.db` guarda só a primeira página de cada conversa (o que aparece ao abrir). As páginas carregadas ao rolar para cima não são gravadas: o botão de leitura do cache nunca as vê.
- Com `511a1ff`/`7de23ab`, o hook dos construtores estáticos de `X.0BMD`/`X.0Bu5` só viu linhas `TEXT` (`X.0BMD.A01`). As linhas de mídia da conversa de teste passam por outro caminho, por exemplo `X.0C0s.Ag6(X.01Fo)` (método de instância), que também cria o modelo visual `X.0E34`.
- Ponto de captura passa a ser o construtor único de `X.01Fo(UserSession, X.019w, X.01hK, X.0ZiN)`, que todo caminho de linha recebe pronto (6 chamadas no DEX).

## Interação com "Visualização única ilimitada"

`GhostPermanentViewHook` (opção `permanentViewMode`) reescreve `view_mode` de `once`/`replayable` para `permanent` no objeto `X.02jG` devolvido pelo parser, antes de o Instagram gravar `direct.db` e antes da captura serializar o modelo. Com a opção ligada, `permanent` deixa de provar que a mídia é permanente. As 4 linhas do teste físico podem ter sido visualização única reescrita.

Correção: o hook passa a registrar o modo original por objeto (`ORIGINAL_BY_OBJECT`, chaves fracas) e a captura ao vivo classifica pelo modo original. A biblioteca usa o modo original para decidir a elegibilidade. A reescrita em memória não autoriza a cópia de uma mídia recebida ainda não aberta. Vale a regra de própria ou já aberta pela conta.

Limite: linhas já gravadas em `direct.db` com a opção ligada não guardam o modo original. A varredura do cache continua classificando essas linhas como permanentes.

## Pendências que dependem de aparelho

1. Dump de uma linha `media` de teste: `adb shell su -c "sqlite3 /data/data/com.instagram.android/databases/direct.db 'select server_item_id, message_type, substr(message,1,2000) from messages where message_type=\"media\" limit 1'"`. Confirma o aninhamento de `image_versions2.candidates` e `video_versions`.
2. Contagem por conversa de teste: quantas linhas de mídia existem antes e depois de rolar o histórico. Mede quanto o cache cobre.
3. Estabilidade de `server_item_id` após force-stop, reinício, refresh, paginação e troca de conta.
4. Verificação de que a ação Salvar e a linha de `direct.db` da mesma mensagem dão o mesmo ID.
5. Rede: com proxy ou `logcat`, confirmar que a consolidação só faz requisições a hosts de CDN e nenhuma a `i.instagram.com`.
6. Visualização única de teste: confirmar que o remetente continua vendo a mídia como não aberta depois de uma consolidação da conversa.

## Transporte e paginação no PR #9

- O log do aparelho mostrou linhas antigas entrando na fila e lotes inteiros saindo com `(no context)`. O hook é instalado durante ou a partir de `Application.attach`. Caso o contexto da aplicação ainda não esteja publicado, o flush usa o contexto base do host. A fila só confirma as linhas depois do aceite do provider e tenta de novo em falhas transitórias.
- Para linhas ainda sem chave própria, `X.01Fo.A0K` aponta para `X.019w`. No APK 454, `X.01CY.A02` passa `X.019w.A0T` a `X.01Fh.A00`, que usa `X.00OP.A00` para criar `DirectThreadKey`. A prévia pr.46 usava o campo errado (`A0V`) e descartou linhas antigas antes da fila. Se o metadado continuar sem chave, a captura usa a conversa passada ao gerador nativo do mesmo lote (`X.01CY.A02`, quarto argumento, chave por `X.09WC.Ccj()`), com a conta do próprio gerador. Se as identidades explícitas divergirem, a linha é recusada.
- Em `classes12.dex`, `X.0CHD` lê `items`, `has_older` e `oldest_cursor`. `X.0ANV.A02` mapeia os valores para `X.0R7T.A00`, `A08` e `A05`. `X.0AYL.A0x()` expõe a resposta nativa e `Dpk()` expõe a conversa da requisição; a conta vem da sessão do mesmo objeto de requisição.
- A captura observa essa resposta e oferece as mídias da página inteira, inclusive as que ainda não foram montadas em uma linha. Os logs contêm contagens e presença de cursor. Essa rota precisa ser confirmada no Galaxy. Ela é passiva e nenhuma observação marca uma conversa como consolidada.
- O histórico remoto completo ainda depende de validar a enumeração paginada da inbox e um caminho de leitura por conversa que funcione sem navegar para ela ou enviar recibos. `direct.db` continua sendo uma fonte parcial.

## Balão visual (visualização única, repetição, manter na conversa)

| Fato | Classe | Confiança |
|---|---|---|
| `X.02vL` infla `layout/direct_visual_message` (0x7f0e0506) em `B6O`, faz bind em `AWs(X.0AHX, X.0E34)` e unbind em `Hjg(X.0AHX)` | `X.02vL` | A |
| Holder `X.0AHX`: `A04` = `FrameLayout` do balão, `A05` = ícone, `A06` = rótulo, `A08` = overlay de progresso | `X.02vL.B6O` | A |
| Modelo `X.0E34 extends X.0B79`; identidade em `X.03QJ` (`A0D`), igual à linha convencional: `CpM()` = identificador, `A05` = thread, `A09` = direção. Sessão em `X.02vL.A01` | `X.0E34`, `X.02vL` | A |

Evidência física (Galaxy, `IEDirectTrace`, 27/11 16:14): 27 partes efêmeras com `source_gap=no_versions` (o Instagram remove as versões depois da visualização), 4 delas já guardadas; 11 consultas `inline_lookup result=no_eligible_copy`, 2 delas para mensagens com cópia guardada. `DirectInlinePreview` passa a desenhar a cópia guardada no balão visual e `MediaLibrary.inlineCopy` aceita cópias capturadas e de visualização única.


### Teste físico de 2026-11-27 (tile) e ajustes

- O tile `X.0ThE` recebeu a cópia (21 aplicações, expirado e visualizável). A cópia ia para `X.0Ths.A01` (`message_content_visual_thumbnail_bubble_container`) com `MATCH_PARENT`; esse container é `wrap_content` e, em mídia expirada, a imagem nativa `A03` fica pequena, então a cópia ficava minúscula no canto. Agora a cópia de qualquer balão efêmero tem tamanho de foto (240dp de largura, altura pela proporção entre 140dp e 340dp) e a imagem nativa (`A03`), o overlay de pendência (`A00`), o glifo (`A02`) e o progresso (`A04`) ficam ocultos com restauração compare-and-restore. O container passa a ter o tamanho da cópia.
- Toque na cópia abre direto o visualizador com a cópia da mensagem (extra `open_copy`, que só dispensa a grade; a concessão continua restrita à mesma mensagem). Sem cópia, abre a galeria da mensagem.
- `direct_visual_message` (0x7f0e0506) só é inflado por `X.02vL.B6O`, mas nenhum bind de `X.02vL` apareceu no teste. A pílula "(1) Foto" vista no aparelho vem de outro binder. Diagnóstico `row_binder` (só com o trace ligado): nos despachantes genéricos `X.0BID.A0R` e `X.0BO7.A0R`, uma linha por combinação de definição, modelo e tipo.

### Pílula de visualização única é Compose

- `dumpsys activity top` com a pílula "(1) Photo" na tela: as linhas da `message_list` são `message_content` (View) ou `com.facebook.compose.view.MetaComposeView` com um `AndroidComposeView` sem filhos nativos. A pílula é uma linha Compose. O diagnóstico `row_binder` do mesmo teste só registrou `X.0ThE`, `X.0BNB`, `X.03LC` e `X.0UZF`; nenhum binder de View desenha a pílula.
- As linhas Compose são criadas por `X.0BUa.A0V` (holder `X.0D7T`) e `X.0Bqa.A0V` (holder `X.0D8e`). `X.0D7T` é ligado com o contexto da mensagem `X.01Fo` e desligado em `A0H()`.
- Identidade: as mesmas propriedades de linha das outras rotas, `X.03QH.A03(UserSession, X.01Fo, X.01lY)` → `X.03QJ` (`A04` identificador, `A05` conversa, `A09` própria).
- Apresentação: um `ComposeView` não aceita views filhas. Com cópia da mensagem, a foto é desenhada no `ViewOverlay` da linha, o `AndroidComposeView` fica invisível, a linha recebe a altura da foto e um toque dentro da foto abre a cópia. Tudo é restaurado (compare-and-restore) no unbind, antes de cada bind e ao sair da janela.
- `X.0D8e.A0I` só é registrado (`row_binder`), para saber se alguma pílula usa esse holder.
