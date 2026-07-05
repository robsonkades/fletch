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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the {@link Xml} entry points: input sources, encoding detection,
 * error translation and security hardening.
 */
class XmlTest {

    record Book(String title, Integer year) {}

    static final XmlExtractor<Book> BOOK = book -> new Book(
            book.value("title", String.class),
            book.value("year", Integer.class));

    static final String BOOK_XML = "<book><title>Dune</title><year>1965</year></book>";

    @Nested
    @DisplayName("Input sources")
    class InputSources {

        @Test
        void extractsFromString() {
            Book book = Xml.extract(BOOK_XML, doc -> doc.child("book", BOOK));

            assertEquals(new Book("Dune", 1965), book);
        }

        @Test
        void extractsFromByteArray() {
            byte[] bytes = BOOK_XML.getBytes(StandardCharsets.UTF_8);

            Book book = Xml.extract(bytes, doc -> doc.child("book", BOOK));

            assertEquals(new Book("Dune", 1965), book);
        }

        @Test
        void extractsFromInputStream() {
            InputStream input = new ByteArrayInputStream(BOOK_XML.getBytes(StandardCharsets.UTF_8));

            Book book = Xml.extract(input, doc -> doc.child("book", BOOK));

            assertEquals(new Book("Dune", 1965), book);
        }

        @Test
        void doesNotCloseTheInputStream() {
            AtomicBoolean closed = new AtomicBoolean();
            InputStream input = new ByteArrayInputStream(BOOK_XML.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() {
                    closed.set(true);
                }
            };

            Xml.extract(input, doc -> doc.child("book", BOOK));

            assertFalse(closed.get(), "extract() must leave stream lifecycle with the caller");
        }

        @Test
        void detectsEncodingFromXmlDeclaration() {
            String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><city><name>São Paulo</name></city>";
            byte[] bytes = xml.getBytes(StandardCharsets.ISO_8859_1);

            String name = Xml.extract(bytes, doc -> doc.child("city", c -> c.value("name", String.class)));

            assertEquals("São Paulo", name);
        }
    }

    @Nested
    @DisplayName("Extractor invocation contract")
    class ExtractorContract {

        @Test
        void cursorStartsBeforeTheRootElement() {
            Integer n = Xml.extract("<n>42</n>", doc -> doc.value("n", Integer.class));

            assertEquals(42, n);
        }

        @Test
        void nullExtractorResultIsALegalReturn() {
            assertNull(Xml.extract("<a/>", doc -> doc.child("a", ignored -> null)));
        }
    }

    @Nested
    @DisplayName("Error contract")
    class ErrorContract {

        @Test
        void malformedXmlThrowsXmlException() {
            String mismatchedTags = "<book><title>Dune</book>";

            assertThrows(XmlException.class,
                    () -> Xml.extract(mismatchedTags, doc -> doc.child("book", BOOK)));
        }

        @Test
        void truncatedDocumentThrowsXmlException() {
            String truncated = "<book><title>Du";

            assertThrows(XmlException.class,
                    () -> Xml.extract(truncated, doc -> doc.child("book", BOOK)));
        }

        @Test
        void undeclaredEntityThrowsXmlException() {
            String xml = "<a><b>foo &nope; bar</b></a>";

            assertThrows(XmlException.class,
                    () -> Xml.extract(xml, doc -> doc.child("a", a -> a.value("b", String.class))));
        }

        @Test
        void parseErrorsCarryTheUnderlyingCause() {
            XmlException e = assertThrows(XmlException.class,
                    () -> Xml.extract("<book>", doc -> doc.child("book", BOOK)));

            assertNotNull(e.getCause(), "the StAX-level failure must be preserved as cause");
        }
    }

    @Nested
    @DisplayName("Security hardening")
    class Security {

        @Test
        void doctypeWithExternalEntityIsRejected() {
            String xxe = "<?xml version=\"1.0\"?>"
                    + "<!DOCTYPE r [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                    + "<r><a>&xxe;</a></r>";

            assertThrows(XmlException.class,
                    () -> Xml.extract(xxe, doc -> doc.child("r", r -> r.value("a", String.class))));
        }
    }

    @Nested
    @DisplayName("Name matching (namespaces)")
    class NameMatching {

        @Test
        void defaultNamespaceElementsMatchByPlainName() {
            String xml = "<order xmlns=\"urn:example:orders\"><id>7</id></order>";

            Integer id = Xml.extract(xml, doc -> doc.child("order", o -> o.value("id", Integer.class)));

            assertEquals(7, id);
        }

        @Test
        void prefixedElementsMatchByFullTagName() {
            String xml = "<ns:order xmlns:ns=\"urn:example:orders\"><ns:id>7</ns:id></ns:order>";

            Integer id = Xml.extract(xml, doc -> doc.child("ns:order", o -> o.value("ns:id", Integer.class)));

            assertEquals(7, id);
        }
    }
}
