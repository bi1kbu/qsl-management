package com.bi1kbu.qslmanagement.front;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.theme.ReactivePostContentHandler;

@Component
public class QslPostCardContentHandler implements ReactivePostContentHandler {

    private final QslCardEmbedContentTransformer transformer;

    public QslPostCardContentHandler(QslCardEmbedContentTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public Mono<PostContentContext> handle(@NonNull PostContentContext postContent) {
        return Mono.just(PostContentContext.builder()
            .post(postContent.getPost())
            .content(transformer.transform(postContent.getContent()))
            .raw(postContent.getRaw())
            .rawType(postContent.getRawType())
            .build());
    }
}
