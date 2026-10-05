package com.crispyland.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class IndexBuilderTest {

    private DocumentLoader loader;
    private IndexStore store;
    private EmbeddingPort embedding;
    private RagProperties props;

    private IndexBuilder builder;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader    = mock(DocumentLoader.class);
        store     = mock(IndexStore.class);
        props     = new RagProperties(100, 10, "./data/index",
                new RagProperties.Search(10, 3, 0.3f),
                new RagProperties.Embedding(false, "no-op", 3),
                new RagProperties.Rerank(false, "gpt-oss-120b", 5, 1000, 600),
                new RagProperties.QueryRewrite(false, "gpt-oss-120b"));
        embedding = new NoOpEmbeddingAdapter(props);

        builder = new IndexBuilder(loader, new FixedSizeChunker(), new StructuredChunker(),
                embedding, store, props);
    }

    private LoadedDocument mdDoc(String text) {
        return new LoadedDocument("readme.md", "docs", "md", text);
    }

    @Test
    void fixedStrategyProducesChunksAndSaves() throws IOException {
        when(loader.loadAll()).thenReturn(List.of(
                mdDoc("# Hello\n" + "x".repeat(500))
        ));

        IndexFile result = builder.buildAndSave("fixed");

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.metadata().strategy()).isEqualTo("fixed");
        verify(store).save(any(), anyString());
    }

    @Test
    void structuredStrategyProducesChunksAndSaves() throws IOException {
        when(loader.loadAll()).thenReturn(List.of(
                mdDoc("# Section A\n" + "x".repeat(200) + "\n# Section B\n" + "y".repeat(200))
        ));

        IndexFile result = builder.buildAndSave("structured");

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.metadata().strategy()).isEqualTo("structured");
        verify(store).save(any(), anyString());
    }

    @Test
    void metadataContainsCorrectTotalChunkCount() throws IOException {
        String text = "x".repeat(500); // 500 chars, chunkSize=100, overlap=10 → step=90 → 6 chunks
        when(loader.loadAll()).thenReturn(List.of(
                new LoadedDocument("f.md", "docs", "md", text)
        ));

        IndexFile result = builder.buildAndSave("fixed");

        assertThat(result.metadata().totalChunks()).isEqualTo(result.chunks().size());
    }

    @Test
    void metadataRecordsEmbeddingModelAndDimension() throws IOException {
        when(loader.loadAll()).thenReturn(List.of(mdDoc("x".repeat(200))));

        IndexFile result = builder.buildAndSave("fixed");

        assertThat(result.metadata().embeddingModel()).isEqualTo("none");
        assertThat(result.metadata().embeddingDimension()).isEqualTo(3);
    }

    @Test
    void emptyDocListProducesEmptyIndex() throws IOException {
        when(loader.loadAll()).thenReturn(List.of());

        IndexFile result = builder.buildAndSave("fixed");

        assertThat(result.chunks()).isEmpty();
        assertThat(result.metadata().totalChunks()).isEqualTo(0);
    }

    @Test
    void embeddingDisabledLeavesEmbeddingNull() throws IOException {
        when(loader.loadAll()).thenReturn(List.of(mdDoc("x".repeat(200))));

        IndexFile result = builder.buildAndSave("fixed");

        // embedding.enabled=false in props → embedAll skips, chunks keep null embedding
        assertThat(result.chunks()).allMatch(c -> c.embedding() == null);
    }
}
