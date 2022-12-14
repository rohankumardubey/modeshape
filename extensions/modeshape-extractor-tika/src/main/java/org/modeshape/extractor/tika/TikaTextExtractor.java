/*
 * ModeShape (http://www.modeshape.org)
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * See the AUTHORS.txt file in the distribution for a full listing of 
 * individual contributors. 
 *
 * ModeShape is free software. Unless otherwise indicated, all code in ModeShape
 * is licensed to you under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * ModeShape is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.modeshape.extractor.tika;

import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.DefaultParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.modeshape.common.collection.Collections;
import org.modeshape.common.util.Logger;
import org.modeshape.common.util.StringUtil;
import org.modeshape.graph.text.TextExtractor;
import org.modeshape.graph.text.TextExtractorContext;
import org.modeshape.graph.text.TextExtractorOutput;
import org.xml.sax.ContentHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link TextExtractor} that uses the Apache Tika library.
 * <p>
 * This extractor will automatically discover all of the Tika {@link Parser} implementations that are defined in
 * <code>META-INF/services/org.apache.tika.parser.Parser</code> text files accessible via the current classloader and that contain
 * the class names of the Parser implementations (one class name per line in each file).
 * </p>
 * <p>
 * This text extractor can be configured in a ModeShape configuration by specifying several optional properties:
 * <ul>
 * <li><strong>excludedMimeTypes</strong> - The comma- or whitespace-separated list of MIME types that should be excluded from
 * text extraction, even if there is a Tika Parser available for that MIME type. By default, the MIME types for
 * {@link #DEFAULT_EXCLUDED_MIME_TYPES package files} are excluded, though explicitly setting any excluded MIME types will
 * override these default.</li>
 * <li><strong>includedMimeTypes</strong> - The comma- or whitespace-separated list of MIME types that should be included in text
 * extraction. This extractor will ignore any MIME types in this list that are not covered by Tika Parser implementations.</li>
 * </ul>
 * </p>
 */
public class TikaTextExtractor implements TextExtractor {

    private static final Logger LOGGER = Logger.getLogger(TikaTextExtractor.class);

    /**
     * The MIME types that are excluded by default. Currently, this list consists of:
     * <ul>
     * <li>application/x-archive</li>
     * <li>application/x-bzip</li>
     * <li>application/x-bzip2</li>
     * <li>application/x-cpio</li>
     * <li>application/x-gtar</li>
     * <li>application/x-gzip</li>
     * <li>application/x-tar</li>
     * <li>application/zip</li>
     * <li>application/vnd.teiid.vdb</li>
     * </ul>
     */
    public static final Set<String> DEFAULT_EXCLUDED_MIME_TYPES = Collections.unmodifiableSet("application/x-archive",
                                                                                              "application/x-bzip",
                                                                                              "application/x-bzip2",
                                                                                              "application/x-cpio",
                                                                                              "application/x-gtar",
                                                                                              "application/x-gzip",
                                                                                              "application/x-tar",
                                                                                              "application/zip",
                                                                                              "application/vnd.teiid.vdb");

    private final Set<String> excludedMimeTypes = new HashSet<String>();
    private final Set<String> includedMimeTypes = new HashSet<String>();
    private final Set<String> supportedMediaTypes = new HashSet<String>();

    private final Lock initLock = new ReentrantLock();
    private Integer writeLimit;
    private DefaultParser parser;

    public TikaTextExtractor() {
        this.excludedMimeTypes.addAll(DEFAULT_EXCLUDED_MIME_TYPES);
    }

    @Override
    public boolean supportsMimeType( String mimeType ) {
        if (excludedMimeTypes.contains(mimeType)) return false;
        initialize();
        return includedMimeTypes.isEmpty() ? supportedMediaTypes.contains(mimeType) : supportedMediaTypes.contains(mimeType)
                                                                                      && includedMimeTypes.contains(mimeType);
    }

    @Override
    public void extractFrom( InputStream stream,
                             TextExtractorOutput output,
                             TextExtractorContext context ) throws IOException {
        final DefaultParser parser = initialize();
        Metadata metadata = prepareMetadata(stream, context);

        try {
            ContentHandler textHandler = writeLimit != null ? new BodyContentHandler(writeLimit) : new BodyContentHandler();
            // Parse the input stream ...
            parser.parse(stream, textHandler, metadata, new ParseContext());

            // Record all of the text in the body ...
            output.recordText(textHandler.toString().trim());
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            context.getProblems().addError(e, TikaI18n.errorWhileExtractingTextFrom, context.getInputPath(), e.getMessage());
        }
    }

    /**
     * Creates a new tika metadata object used by the parser. This will contain the mime-type of the content being parsed,
     * if this is available to the underlying context. If not, Tika's autodetection mechanism is used to try and get the
     * mime-type.
     *
     * @param stream a <code>InputStream</code> instance of the content being parsed
     * @param context the text extraction context
     * @return a <code>Metadata</code> instance.
     * @throws java.io.IOException if auto-detecting the mime-type via Tika fails
     */
    private Metadata prepareMetadata( InputStream stream, TextExtractorContext context ) throws IOException {
        Metadata metadata = new Metadata();

        if (StringUtil.isBlank(context.getMimeType())) {
            LOGGER.warn(TikaI18n.warnMimeTypeNotSet);
            metadata.set(Metadata.RESOURCE_NAME_KEY, context.getInputPath().getLastSegment().getString());
            if (stream.markSupported()) {
                MediaType autoDetectedMimeType = new DefaultDetector(this.getClass().getClassLoader()).detect(stream, metadata);
                metadata.set(Metadata.CONTENT_TYPE, autoDetectedMimeType.toString());
            }
        }
        else {
            metadata.set(Metadata.CONTENT_TYPE, context.getMimeType());
        }
        return metadata;
    }

    /**
     * This class lazily initializes the {@link DefaultParser} instance.
     *
     * @return the default parser; same as {@link #parser}
     */
    protected DefaultParser initialize() {
        if (parser == null) {
            try {
                initLock.lock();
                if (parser == null) {
                    parser = new DefaultParser(this.getClass().getClassLoader());
                }
                Map<MediaType, Parser> parsers = parser.getParsers();
                for (MediaType mediaType : parsers.keySet()) {
                    // Don't use the toString() method, as it may append properties ...
                    String mimeType = mediaType.getType() + "/" + mediaType.getSubtype();
                    supportedMediaTypes.add(mimeType);
                }
            } finally {
                initLock.unlock();
            }
        }
        return parser;
    }

    /**
     * Get the MIME types that are explicitly requested to be included. This list may not correspond to the MIME types that can be
     * handled via the available Parser implementations.
     *
     * @return the set of MIME types that are to be included; never null
     */
    public Set<String> getIncludedMimeTypes() {
        return Collections.unmodifiableSet(includedMimeTypes);
    }

    /**
     * Set the MIME types that should be included. This method clears all previously-set excluded MIME types.
     *
     * @param includedMimeTypes the whitespace-delimited or comma-separated list of MIME types that are to be included
     */
    public void setIncludedMimeTypes( String includedMimeTypes ) {
        if (includedMimeTypes == null || includedMimeTypes.length() == 0) return;
        this.includedMimeTypes.clear();
        for (String mimeType : includedMimeTypes.split("[,\\s]")) {
            includeMimeType(mimeType);
        }
    }

    /**
     * Add another MIME type that should be excluded. This method does not clear any included MIME types that were previously set.
     *
     * @param includedMimeType the MIME type that is to be included
     */
    public void addIncludedMimeType( String includedMimeType ) {
        if (includedMimeType == null || includedMimeType.length() == 0) return;
        includeMimeType(includedMimeType);
    }

    /**
     * Include the MIME type from extraction.
     *
     * @param mimeType MIME type that should be included
     */
    public void includeMimeType( String mimeType ) {
        if (mimeType == null) return;
        mimeType = mimeType.trim();
        if (mimeType.length() != 0) includedMimeTypes.add(mimeType);
    }

    /**
     * Set the MIME types that should be excluded.
     *
     * @return the set of MIME types that are to be excluded; never null
     */
    public Set<String> getExcludedMimeTypes() {
        return Collections.unmodifiableSet(excludedMimeTypes);
    }

    /**
     * Set the MIME types that should be excluded. This method clears all previously-set excluded MIME types.
     *
     * @param excludedMimeTypes the whitespace-delimited or comma-separated list of MIME types that are to be excluded
     */
    public void setExcludedMimeTypes( String excludedMimeTypes ) {
        if (excludedMimeTypes == null || excludedMimeTypes.length() == 0) return;
        this.excludedMimeTypes.clear();
        for (String mimeType : excludedMimeTypes.split("[,\\s]")) {
            excludeMimeType(mimeType);
        }
    }

    /**
     * Add another MIME type that should be excluded. This method does not clear any excluded MIME types that were previously set.
     *
     * @param excludedMimeType the MIME type that is to be excluded
     */
    public void addExcludedMimeType( String excludedMimeType ) {
        if (excludedMimeType == null || excludedMimeType.length() == 0) return;
        excludeMimeType(excludedMimeType);
    }

    /**
     * Exclude the MIME type from extraction.
     *
     * @param mimeType MIME type that should be excluded
     */
    public void excludeMimeType( String mimeType ) {
        if (mimeType == null) return;
        mimeType = mimeType.trim();
        if (mimeType.length() != 0) excludedMimeTypes.add(mimeType);
    }

    /**
     * Sets the write limit for the Tika parser, representing the maximum number of characters that should be extracted by the
     * TIKA parser.
     *
     * @param writeLimit a {@link String} which represents the write limit; may be null
     * @see BodyContentHandler#BodyContentHandler(int)
     */
    public void setWriteLimit( String writeLimit ) {
        try {
            setWriteLimit(Integer.valueOf(writeLimit));
        } catch (NumberFormatException e) {
            LOGGER.debug("Invalid value for writeLimit parameter: " + writeLimit);
        }
    }

    /**
     * Sets the write limit for the Tika parser, representing the maximum number of characters that should be extracted by the
     * TIKA parser.
     *
     * @param writeLimit an {@link Integer} which represents the write limit; may be null
     * @see BodyContentHandler#BodyContentHandler(int)
     */
    public void setWriteLimit( Integer writeLimit ) {
        this.writeLimit = writeLimit;
    }
}
