package com.bi1kbu.qslmanagement.front;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.theme.ReactivePostContentHandler;

@Component
public class QslPostCardContentHandler implements ReactivePostContentHandler {

    private final QslCardEmbedContentTransformer cardTransformer;
    private final QslReceiptEmbedContentTransformer receiptTransformer;
    private final QslExchangeEmbedContentTransformer exchangeTransformer;

    public QslPostCardContentHandler(
        QslCardEmbedContentTransformer cardTransformer,
        QslReceiptEmbedContentTransformer receiptTransformer,
        QslExchangeEmbedContentTransformer exchangeTransformer
    ) {
        this.cardTransformer = cardTransformer;
        this.receiptTransformer = receiptTransformer;
        this.exchangeTransformer = exchangeTransformer;
    }

    @Override
    public Mono<PostContentContext> handle(@NonNull PostContentContext postContent) {
        var transformed = exchangeTransformer.transform(
            receiptTransformer.transform(cardTransformer.transform(postContent.getContent()))
        );
        return Mono.just(PostContentContext.builder()
            .post(postContent.getPost())
            .content(transformed)
            .raw(postContent.getRaw())
            .rawType(postContent.getRawType())
            .build());
    }
}
