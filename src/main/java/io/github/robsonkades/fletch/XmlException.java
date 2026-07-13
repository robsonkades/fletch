/*
 * Copyright 2026 Robson Kades
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;


import java.io.Serial;

/**
 * The single exception type thrown by the Fletch API.
 *
 * <p>Covers every failure mode — malformed documents, stream I/O errors and
 * unsupported type conversions — so callers handle one unchecked exception.
 *
 * <p>Parse failures carry the byte offset of the problem in the source
 * document in their message. When the failure originates in an underlying
 * I/O operation, the original exception is preserved as the
 * {@linkplain #getCause() cause}.
 */
public final class XmlException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -6782883390043410247L;

    /**
     * Creates an exception wrapping an underlying parser or I/O failure.
     *
     * @param message description of the failed operation
     * @param cause   the underlying exception
     */
    public XmlException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates an exception for a failure detected by Fletch itself
     * (contract violations, unsupported conversions).
     *
     * @param message description of the failure
     */
    public XmlException(String message) {
        super(message);
    }
}
