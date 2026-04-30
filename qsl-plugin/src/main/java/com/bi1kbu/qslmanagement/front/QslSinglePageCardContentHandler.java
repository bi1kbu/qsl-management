package com.bi1kbu.qslmanagement.front;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.theme.ReactiveSinglePageContentHandler;

@Component
public class QslSinglePageCardContentHandler implements ReactiveSinglePageContentHandler {

    private final QslCardEmbedContentTransformer cardTransformer;
    private final QslReceiptEmbedContentTransformer receiptTransformer;
    private final QslExchangeEmbedContentTransformer exchangeTransformer;

    public QslSinglePageCardContentHandler(
        QslCardEmbedContentTransformer cardTransformer,
        QslReceiptEmbedContentTransformer receiptTransformer,
        QslExchangeEmbedContentTransformer exchangeTransformer
    ) {
        this.cardTransformer = cardTransformer;
        this.receiptTransformer = receiptTransformer;
        this.exchangeTransformer = exchangeTransformer;
    }

    @Override
    public Mono<SinglePageContentContext> handle(@NonNull SinglePageContentContext singlePageContent) {
        var transformed = exchangeTransformer.transform(
            receiptTransformer.transform(cardTransformer.transform(singlePageContent.getContent()))
        );
        return Mono.just(SinglePageContentContext.builder()
            .singlePage(singlePageContent.getSinglePage())
            .content(transformed)
            .raw(singlePageContent.getRaw())
            .rawType(singlePageContent.getRawType())
            .build());
    }
}
