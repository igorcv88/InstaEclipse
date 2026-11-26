# Evidências de compatibilidade da biblioteca

| Insumo | Identificação |
|---|---|
| InstaEclipse, base do port | `main` em `483e8e14ad99e2b0ff2631b5ba124e6750e22b57` |
| WaEnhancerXCommunity | PR #176, `36b6593c5343bd73a7ba7921c66b0fe42055d6d6`, CI de referência 45889148424 |
| Instagram | 454.0.0.0.55, versionCode 386005100 |
| SHA-256 do pacote original conferido | `98f61046fb62a109f0000a30b9ef022839fa2153e77edeb228e6a3802d672657` |
| SHA-256 do `base.apk` extraído | `3419bb746e9f78cfdcca3794621e63fcde7317f81dc3b36ba2fe6b75a992b4c8` |

A inspeção comparou os hashes, leu as definições de classe no DEX e verificou a tabela de recursos com `aapt2 dump resources`. `classes2.dex` define `com.instagram.model.direct.DirectThreadKey` e `classes4.dex` define `com.instagram.model.direct.messageid.DirectMessageIdentifier`. A tabela confirma os IDs `direct_thread_header` e `shared_media_list`, e os layouts `direct_thread_details_tab_view`, `layout_thread_detail_shared_photos_and_videos` e `layout_thread_detail_shared_photos_and_videos_item`. Essa inspeção não identificou `DirectThreadSharedMediaFragment` como classe definida pelo mesmo nome nos DEX do APK-base. O nome citado no handoff continua candidato.

A existência do modelo de chave não confirma o significado de cada campo, o componente ativo e a estabilidade de um ID de mensagem após atualização do app. A implementação reaproveita a resolução da conversa existente e mantém a biblioteca e o visualizador próprios. A aba nativa e a miniatura no balão permanecem pendentes de observação em runtime. O corpus original fica fora do Git.

## Verificação física

No Galaxy com o Instagram 454 e contas de teste:

1. Instale a prévia assinada como atualização e abra o Direct com o atalho desligado. Leia uma DM, feche o Instagram, reabra e atualize o Direct. Observe se o aplicativo a mantém como lida e deixa de reenviar a notificação.
2. Ligue o atalho em Mídia → Downloads e abra a biblioteca pelo menu do Direct. Importe uma foto e um vídeo de teste. Confirme que aparecem na conversa correta e ficam ausentes da galeria de outra conversa ou conta.
3. Remova a fonte fornecida, force o encerramento dos apps, reinicie o aparelho e abra as cópias com modo avião. Confirme zoom, reprodução e navegação no visualizador.
4. Exporte uma cópia e verifique a Samsung Gallery. Teste lixeira, quota e recuperação após interrupção. Exporte um backup de teste, rejeite senha errada e restaure com a senha correta.
5. Repita a leitura de uma DM com o atalho ligado. Confirme que os recibos e o comportamento nativo de mensagens efêmeras conservam o estado esperado.
