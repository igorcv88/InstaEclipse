# Biblioteca de mídias

A biblioteca guarda arquivos que o usuário escolhe pelo seletor de documentos do Android. A cópia abre no aplicativo InstaEclipse, inclusive com a fonte removida e o Instagram fechado. A operação não lê o banco, cache nem estado das mensagens do Direct. As rotinas de recibo de leitura e download preexistentes não fazem parte do fluxo.

## Catálogo e armazenamento

O catálogo SQLite começa na versão 1. Uma migração valida a integridade e a contagem de linhas em transação. Versões futuras são recusadas. A tabela `accounts` delimita o pacote e a identidade da conta. `items` guarda UUID, thread, chave de mensagem opcional, direção, origem, MIME, hash e datas. `blobs` guarda um registro por SHA-256 e o estado dos bytes. `meta` guarda quota e geração. O formato e os identificadores do WaEnhancer não são aceitos.

Um arquivo selecionado na biblioteca principal tem origem `imported` e começa sem conversa. Abrir a biblioteca pelo Direct permite importar para a conversa ou associar uma cópia local explicitamente. Esses itens continuam sem chave de mensagem. O catálogo suporta identidade completa e mantém cópias em escopos diferentes mesmo quando os bytes são iguais. Duas cópias divergentes com a mesma identidade de mensagem ficam disponíveis na biblioteca e tornam a busca dessa mensagem ambígua.

Os bytes ficam abaixo de `files/media_library`. O staging calcula SHA-256 em streaming, valida formato e estrutura, consulta o decodificador Android e sincroniza o arquivo antes de publicar o blob. O SQLite grava o vínculo depois da publicação. Sessões privadas de staging mantêm um lease entre processos. A recuperação pode limpar sessões abandonadas sem interromper uma importação ativa. O lock do catálogo serializa publicação, quota e mutações de estado. Leituras do catálogo usam WAL. A verificação profunda compete com outras mutações pelo lock.

O limite inicial é 16 GiB. O menu permite escolher entre 1 e 256 GiB, com reserva de 512 MiB no volume e limite de 4 GiB por item. Bytes iguais verificados só contam uma vez. Itens ativos não expiram. A lixeira tem retenção de 30 dias. Um blob compartilhado permanece enquanto existir alguma referência. Verificar arquivos reporta ausências, danos, órfãos, quarentena e ambiguidades. A verificação periódica funciona enquanto o processo do módulo está ativo. Roda na primeira abertura e a cada seis horas, com lote nominal de 256 MiB e pelo menos um blob por rodada. Um blob grande pode exceder esse orçamento.

O catálogo, seus arquivos auxiliares SQLite, os blobs, staging, quarentena e journals são excluídos do backup automático e da transferência automática do Android.

## Importação e visualização

A importação aceita até 200 arquivos por seleção. O seletor do Android identifica a origem e a confirmação mostra o lote antes da gravação. A ação Pausar interrompe a leitura do arquivo em andamento. O checkpoint avança depois do resultado de cada item e guarda as permissões do seletor necessárias para retomar. Se o processo terminar entre o commit e a gravação do checkpoint, reprocessar o item devolve a cópia já existente. Há um lote pendente por instalação. Abra o mesmo escopo em que o lote começou para retomá-lo ou descartá-lo. O visualizador usa a cópia local e oferece zoom e navegação. O teste de vídeo valida os bytes, a trilha e a duração. Ele não certifica a decodificação de todos os quadros.

No Android 10 ou superior, exportar cria uma cópia explícita em `DCIM/Instagram Library`. A origem `normal` é reservada para vínculos comprovados e usa `DCIM/Instagram`. O MediaStore recebe a entrada pendente e só publica depois do fechamento do stream e de uma atualização bem-sucedida. Repetir a ação cria outra cópia. O Android 9 usa o seletor para escolher o destino. O jornal recupera entradas pendentes conhecidas após interrupção. Há uma janela entre inserção e registro do URI em que a limpeza fica a cargo do MediaStore.

## Direct e IPC

O atalho do Direct é opt-in e usa o menu existente da conversa. `CurrentConversation` confirma o escopo antes da solicitação e novamente antes de abrir a tela. O provider confere o UID e o pacote por meio da autenticação existente em `IpcSecurity`. Apenas o escopo da conversa cruza a fronteira. A atividade de entrada confere uma capacidade de 60 segundos, de uso único, e abre uma atividade privada. As contas do Instagram compartilham UID e o Android autentica o pacote. A conta vem da conversa ativa revalidada pelo módulo.

A galeria própria funciona como fallback para o Direct 454. A apresentação de miniaturas nos balões e uma aba dentro da galeria nativa exigem evidência em execução do componente e da identidade por mensagem. A galeria própria aceita vínculos com a conversa independentemente dessas integrações. O módulo não infere um vínculo por timestamp, nome de arquivo ou posição visual.

## Backup explícito

O backup `.ielib` usa o contêiner versão 1, PBKDF2-HMAC-SHA256 e blocos autenticados com AES-256-GCM. Os bytes são transmitidos em fluxo. A restauração confere o catálogo, a autenticação, hashes, MIME e estrutura antes do commit. O merge preserva cópias locais e é idempotente por escopo e hash. A exportação informa quantos itens estavam indisponíveis. O manifesto JSON tem limite de 16 MiB independente do tamanho dos blobs. Guarde a senha com o arquivo.

## Build e atribuição

JDK 17, Android SDK 34 e Build Tools 35.0.0, sem mudar o minSdk 28.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

O workflow oficial executa os checks e assina a prévia dos PRs autorizados. Os links e os resultados ficam na página do PR. Veja a [matriz de testes](MEDIA_LIBRARY_TEST_MATRIX.md) e as [evidências do APK](MEDIA_LIBRARY_EVIDENCE.md).

O núcleo de blob, SQL, backup, validação, integridade e parte das telas e dos testes foram adaptados do [WaEnhancerXCommunity PR #176, commit 36b6593](https://github.com/igorcv88/WaEnhancerXCommunity/tree/36b6593c5343bd73a7ba7921c66b0fe42055d6d6/app/src/main/java/com/waenhancer/library), sob GPL-3.0. A política de importação, os vínculos e a integração do Direct pertencem a este projeto.
