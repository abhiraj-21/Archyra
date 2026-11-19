package com.abhiraj.archyra.ai.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import com.abhiraj.archyra.ai.AiProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Local embeddings: an ONNX MiniLM-class model run inside the JVM through Spring AI Transformers. No API key, no rate
 * limit. The model is loaded on first use (it is about 90 MB, and most of the application never needs it) and
 * {@link EmbeddingStartupCheck} loads it at startup in production so that a missing file or a model of the wrong size
 * stops the application instead of failing the first analysis's index.
 *
 * <p>Only Spring AI's {@code EmbeddingModel} is used: vectors are stored in our own {@code code_chunks} table, not in
 * Spring AI's {@code PgVectorStore}.
 */
@Slf4j
@Service
public class EmbeddingService implements DisposableBean {

	/** MiniLM-class models read at most about this many word pieces; longer input is cut by the tokenizer. */
	private static final String MAX_WORD_PIECES = "256";

	private final AiProperties properties;
	private volatile TransformersEmbeddingModel model;
	/**
	 * Inference holds the read lock, closing the model takes the write lock. The ONNX session is native memory: freeing it
	 * under a running inference is a use-after-free that crashes the whole JVM (it did, when a Spring context was closed while
	 * an embedding job ran), so {@link #destroy} waits for the batches in flight and later calls are refused.
	 */
	private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
	private boolean closed;

	public EmbeddingService(AiProperties properties) {
		this.properties = properties;
	}

	/** One vector of {@link EmbeddingSpec#DIMENSION} floats. */
	public float[] embed(String text) {
		return embedAll(List.of(text)).getFirst();
	}

	/** Vectors for {@code texts}, in order. */
	public List<float[]> embedAll(List<String> texts) {
		if (texts.isEmpty()) {
			return List.of();
		}
		lifecycle.readLock().lock();
		try {
			requireOpen();
			List<float[]> vectors = model().embed(texts);
			for (float[] vector : vectors) {
				if (vector.length != EmbeddingSpec.DIMENSION) {
					throw new IllegalStateException("The embedding model returned " + vector.length
							+ " values; the code_chunks column holds " + EmbeddingSpec.DIMENSION);
				}
			}
			return vectors;
		}
		finally {
			lifecycle.readLock().unlock();
		}
	}

	/** Loads the model and proves that its output size is the column's. Throws if either is not so. */
	public void verify() {
		lifecycle.readLock().lock();
		try {
			requireOpen();
			verifyDimension(model().embed("verify"));
		}
		finally {
			lifecycle.readLock().unlock();
		}
	}

	private void requireOpen() {
		if (closed) {
			throw new EmbeddingClosedException();
		}
	}

	private static void verifyDimension(float[] probe) {
		if (probe.length != EmbeddingSpec.DIMENSION) {
			throw new IllegalStateException("The embedding model " + EmbeddingSpec.MODEL_NAME + " returns "
					+ probe.length + " values but code_chunks.embedding is vector(" + EmbeddingSpec.DIMENSION
					+ "). Fix archyra.ai.embedding.* (or the column) and re-index.");
		}
	}

	/** Releases the native ONNX session when the application context closes, after the batches in flight have finished. */
	@Override
	public void destroy() {
		lifecycle.writeLock().lock();
		try {
			closed = true;
			TransformersEmbeddingModel loaded = model;
			model = null;
			if (loaded != null) {
				try {
					loaded.close();
				}
				catch (Exception ex) {
					log.warn("The embedding model did not close cleanly ({})", ex.getClass().getSimpleName());
				}
			}
		}
		finally {
			lifecycle.writeLock().unlock();
		}
	}

	private TransformersEmbeddingModel model() {
		TransformersEmbeddingModel loaded = model;
		if (loaded == null) {
			synchronized (this) {
				loaded = model;
				if (loaded == null) {
					loaded = load();
					model = loaded;
				}
			}
		}
		return loaded;
	}

	private TransformersEmbeddingModel load() {
		AiProperties.Embedding settings = properties.embedding();
		try {
			Path cache = Path.of(settings.cacheDir()).toAbsolutePath();
			Files.createDirectories(cache);
			TransformersEmbeddingModel created = new TransformersEmbeddingModel();
			created.setResourceCacheDirectory(cache.toString());
			created.setModelResource(settings.modelUri());
			created.setTokenizerResource(settings.tokenizerUri());
			created.setTokenizerOptions(Map.of("padding", "true", "truncation", "true", "maxLength", MAX_WORD_PIECES));
			created.afterPropertiesSet();
			log.info("Embedding model {} loaded (cache {})", EmbeddingSpec.MODEL_NAME, cache);
			return created;
		}
		catch (Exception ex) {
			throw new IllegalStateException("The embedding model could not be loaded. Run scripts/fetch-embedding-model.sh "
					+ "once (it downloads the ONNX model into " + settings.cacheDir() + "/), or check "
					+ "archyra.ai.embedding.model-uri and tokenizer-uri.", ex);
		}
	}
}
