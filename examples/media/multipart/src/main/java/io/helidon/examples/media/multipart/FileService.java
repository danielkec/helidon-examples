/*
 * Copyright (c) 2020, 2024 Oracle and/or its affiliates.
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
package io.helidon.examples.media.multipart;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.stream.Stream;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.ContentDisposition;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.http.HeaderValues;
import io.helidon.http.ServerResponseHeaders;
import io.helidon.http.media.multipart.MultiPart;
import io.helidon.http.media.multipart.ReadablePart;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonBuilderFactory;

import static io.helidon.http.Status.BAD_REQUEST_400;
import static io.helidon.http.Status.MOVED_PERMANENTLY_301;
import static io.helidon.http.Status.NOT_FOUND_404;

/**
 * File service.
 */
public final class FileService implements HttpService {
    private static final Header UI_LOCATION = HeaderValues.createCached(HeaderNames.LOCATION, "/ui");
    private final JsonBuilderFactory jsonFactory;
    private final Path storage;

    /**
     * Create a new file upload service instance.
     */
    FileService() {
        jsonFactory = Json.createBuilderFactory(Map.of());
        storage = createStorage();
        System.out.println("Storage: " + storage);
    }

    @Override
    public void routing(HttpRules rules) {
        rules.get("/", this::list)
             .get("/{fname}", this::download)
             .post("/", this::upload);
    }

    private static Path createStorage() {
        try {
            return Files.createTempDirectory("fileupload");
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Stream<String> listFiles(Path storage) {
        try (Stream<Path> walk = Files.walk(storage)) {
            return walk.filter(Files::isRegularFile)
                       .map(storage::relativize)
                       .map(Path::toString)
                       .toList()
                       .stream();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static OutputStream newOutputStream(Path storage, String fname) {
        try {
            return Files.newOutputStream(storage.resolve(fname),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void list(ServerRequest req, ServerResponse res) {
        JsonArrayBuilder arrayBuilder = jsonFactory.createArrayBuilder();
        listFiles(storage).forEach(arrayBuilder::add);
        res.send(jsonFactory.createObjectBuilder().add("files", arrayBuilder).build());
    }

    private void download(ServerRequest req, ServerResponse res) {
        Path filePath = storage.resolve(req.path().pathParameters().get("fname"));
        if (!filePath.getParent().equals(storage)) {
            res.status(BAD_REQUEST_400).send("Invalid file name");
            return;
        }
        if (!Files.exists(filePath)) {
            res.status(NOT_FOUND_404).send();
            return;
        }
        if (!Files.isRegularFile(filePath)) {
            res.status(BAD_REQUEST_400).send("Not a file");
            return;
        }
        ServerResponseHeaders headers = res.headers();
        headers.contentType(MediaTypes.APPLICATION_OCTET_STREAM);
        headers.set(ContentDisposition.builder()
                                      .filename(filePath.getFileName().toString())
                                      .build());
        res.send(filePath);
    }

    private void upload(ServerRequest req, ServerResponse res) {
        MultiPart mp = req.content().as(MultiPart.class);

        while (mp.hasNext()) {
            ReadablePart part = mp.next();
            if ("file[]".equals(URLDecoder.decode(part.name(), StandardCharsets.UTF_8))) {
                try (InputStream in = part.inputStream(); OutputStream out = newOutputStream(storage, part.fileName().get())) {
                    in.transferTo(out);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write content", e);
                }
            }
        }

        res.status(MOVED_PERMANENTLY_301)
           .header(UI_LOCATION)
           .send();
    }
}
