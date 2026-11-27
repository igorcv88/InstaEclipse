# Matriz de testes da biblioteca de mídias

| Critério | Evidência coberta no host | Evidência Android e Direct |
|---|---|---|
| Formato e integridade | JPEG, PNG e MP4 estruturais, vazio, formato desconhecido, truncamento, falha de decode e falha de close | `MediaLibraryDeviceTest` usa JPEG, PNG, WebP e H.264 reais no decodificador Android |
| Identidade | Isolamento por conta/thread, referências distintas com bytes iguais, vínculo desconhecido e conflito de conteúdo | Vínculo por mensagem e troca de conta no Direct exigem aparelho |
| Lote | Idempotência, persistência, fluxo interrompido, cleanup de staging, quota e espaço disponível | Pausa e retomada pelo seletor exigem interação com o Android |
| Persistência | Processo novo com rede bloqueada após remoção da fonte, manutenção simulada de 1 a 730 dias | Reinício e modo avião no Galaxy pendentes |
| Concorrência | Conexões SQLite independentes em threads e em dois processos, rollback transacional e lease de staging | Volume grande em aparelho e medição de ANR pendentes |
| Recuperação | Corrupção de mesmo tamanho, reparo, órfão em quarentena, arquivo ausente, preservação de referências e retenção da lixeira | Interrupção física pendente |
| Migração | Criação, reabertura, etapa aditiva, rollback, proteção contra remoção e rejeição de versão futura | Adaptador SQLite Android coberto em `MediaLibraryDeviceTest` |
| Backup | Round trip, duplicidade, conflito, autenticação, adulteração, truncamento, quota e 160 MiB em heap de 48 MiB | Seletor/criação de documento e restauração física pendentes |
| UI e playback | Visibilidade baseada em escopo e resolução de colisões no catálogo | Tela própria instrumentada, playback e zoom físicos pendentes, sem integração visual no balão |
| IPC | Token, escopo, expiração e uso único | Provider recusa o UID da instrumentação. Validação com o processo real do Instagram pendente |
| MediaStore | Ordem de fechamento/publicação, inserção recusada, falha de escrita, close, update zero e exceção | Teste instrumentado compara bytes e `IS_PENDING`; APIs 28 e 37 e morte durante exportação pendentes |
| Cobertura do Direct | `DirectCoverageTest` (12): páginas repetidas, embaralhadas e nas duas direções; ausência nunca apaga nem invalida; falha transitória nunca conta como processada; limite de tentativas; início não alcançado nunca é completo; indisponível que se resolve depois; mídia incompleta recusada; ambiguidade sem escolha; isolamento por conta, conversa e direção; processo novo sem rede | Contagens reais da conversa de teste pendentes |
| Consolidação | `DirectConsolidationTest` (11): parser de linha, recusa de JSON contraditório, somente hosts de CDN, visualização única nunca baixada, idempotência de reoferta, pausa, reinício e falha transitória, URL expirada, linha removida do cache mantém a cópia, oferta fora do escopo, página IPC malformada recusada inteira, migração v1 → v2 | Dump de linha real, rede observada e visualização única não aberta pendentes |
| Correções da revisão | `DirectReviewFixesTest` (7): transferência cortada continua tentável; pausa no meio do arquivo sem resultado nem tentativa; pausa persistente; reinício explícito das falhas; disponibilidade atual e reparo a partir da mesma mensagem; reparo com URL expirada não repete; carrosséis divididos em lotes aceitos. `ReviewFindingsTest` (2): quota de restauração e corrupção conhecida | Corrida do worker e pausa em aparelho pendentes |
| Regressões do Direct | Suíte existente de preferências, contexto da conversa e política de recibos | Reproduzir refresh, force-stop e notificações no Instagram 454 |

## Execução e mutações

O host usa JDK 17, Gradle 8.10.2, Android SDK 34, Build Tools 35.0.0 e SQLite JDBC 3.46.1.3. A suíte com FFmpeg e FFprobe habilitados executou 436 testes, 42 da biblioteca, com zero falhas e zero casos ignorados. O comando padrão de CI sem os binários explícitos deixa dois testes FFmpeg da suíte preexistente de MP4 em skip. O APK de instrumentação é compilado separadamente de sua execução.

```sh
./gradlew -Dinstaeclipse.ffmpeg=/usr/bin/ffmpeg -Dinstaeclipse.ffprobe=/usr/bin/ffprobe \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Seis mutações da etapa 2 foram executadas e revertidas, cada uma derrubando pelo menos um teste: enviar visualização única para download (`viewOnceItemsAreRecordedButNeverFetched`); seguir depois de falha transitória (`interruptionAndTransientFailureResumeWithoutLossOrDuplicates`); apagar o que sumiu da página (cinco testes); rebaixar estado final (dois testes); aceitar qualquer host HTTPS (`onlyMediaCdnHostsAreEverAccepted`); deixar `reached_start` voltar a falso (dois testes).

Duas mutações foram executadas sobre cópias temporárias do código. Fazer uma busca de mensagem devolver a primeira linha de um conflito derrubou `aCollisionHidesTheMessageLookupAndKeepsBothCopies`. Aceitar zero linhas atualizadas na publicação derrubou `exportFailsAndCleansUpWhenPublishWriteOrCloseFails`. A rodada mutada executou 10 casos e falhou exatamente nesses dois. As mutações ficaram fora deste commit.
